package com.icu.smart_cold_storage_monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.ThresholdChangeRequest;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.model.ThresholdRequestStatus;
import com.icu.smart_cold_storage_monitor.model.ThresholdSetting;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.repository.ThresholdChangeRequestRepository;
import com.icu.smart_cold_storage_monitor.service.ThresholdService.SubmissionOutcome;
import com.icu.smart_cold_storage_monitor.service.exception.ConflictException;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Default limits per location, session resets, and request bookkeeping against a temporary SQLite database. */
class ThresholdServiceTest extends SqliteIntegrationTest {

    static final String DEVICE = "ESP32-SIM-001";
    /** RAW-MEAT and fallback defaults from application.yaml. */
    static final ThresholdLimits RAW_MEAT = new ThresholdLimits(0, 4, 55, 80);
    static final ThresholdLimits FALLBACK = new ThresholdLimits(0, 8, 30, 95);
    static final ThresholdLimits CUSTOM = new ThresholdLimits(1, 5, 60, 85);

    @Autowired
    ThresholdService thresholds;

    @Autowired
    DeviceSessionService deviceSessions;

    @Autowired
    UserAccountService accounts;

    @Autowired
    ThresholdChangeRequestRepository requests;

    @Autowired
    AuditEventRepository auditEvents;

    long admin;
    long operator;

    @BeforeEach
    void users() {
        admin = accounts.createUser("admin1", "correct-horse-1", Role.ADMIN, null).user().id();
        operator = accounts.createUser("operator1", "correct-horse-1", Role.OPERATOR, null).user().id();
    }

    @Test
    void aNewSessionStartsWithItsLocationDefaultAndTheSessionAuditNamesIt() {
        long session = deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat", "RAW-MEAT", "RAW-MEAT").id();
        deviceSessions.ensureDeviceWithActiveSession("ESP32-X", "Unknown zone", "NOT-CONFIGURED", "X");
        deviceSessions.ensureDeviceWithActiveSession("ESP32-Y", "No zone", null, "UNSPECIFIED");

        ThresholdSetting current = current(DEVICE);
        assertThat(current.sessionId()).isEqualTo(session);
        assertThat(current.limits()).isEqualTo(RAW_MEAT);
        assertThat(current.systemDefault()).isTrue();
        assertThat(current("ESP32-X").limits()).isEqualTo(FALLBACK);
        assertThat(current("ESP32-Y").limits()).isEqualTo(FALLBACK);
        AuditEvent started = auditEvents.findRecent(10).stream()
                .filter(e -> e.eventType() == AuditEventType.STORAGE_SESSION_STARTED
                        && e.targetId().equals(String.valueOf(session)))
                .findFirst().orElseThrow();
        assertThat(started.details()).contains("thresholdId=" + current.id());
    }

    @Test
    void aNewSessionResetsCustomLimitsToTheDefaultAndSupersedesThePendingRequest() {
        long first = deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat", "RAW-MEAT", "RAW-MEAT").id();
        thresholds.submit(admin, DEVICE, CUSTOM);
        long requestId = thresholds.submit(operator, DEVICE, new ThresholdLimits(2, 6, 60, 85)).request().id();
        jdbc.sql("UPDATE storage_sessions SET ended_at = started_at WHERE id = ?").param(first).update();

        long second = deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat", "RAW-MEAT", "RAW-MEAT").id();

        ThresholdSetting current = current(DEVICE);
        assertThat(current.sessionId()).isEqualTo(second);
        assertThat(current.limits()).isEqualTo(RAW_MEAT);
        ThresholdChangeRequest superseded = requests.findById(requestId).orElseThrow();
        assertThat(superseded.status()).isEqualTo(ThresholdRequestStatus.SUPERSEDED);
        assertThat(superseded.reviewedBy()).isNull();
        assertThat(superseded.reviewNote()).contains("session " + second);
    }

    @Test
    void activeSessionsWithoutLimitsAreGivenTheirDefaultOnce() {
        // Sessions created before thresholds existed: inserted directly, without a version.
        jdbc.sql("INSERT INTO devices (id, name, location_code) VALUES (?, 'Frozen', 'FROZEN')").param(DEVICE).update();
        jdbc.sql("INSERT INTO devices (id, name) VALUES ('ESP32-X', 'Ended')").update();
        jdbc.sql("""
                INSERT INTO storage_sessions (device_id, product_type, started_at)
                VALUES (?, 'FROZEN', '2026-11-12T08:00:00.000Z')""").param(DEVICE).update();
        jdbc.sql("""
                INSERT INTO storage_sessions (device_id, product_type, started_at, ended_at)
                VALUES ('ESP32-X', 'X', '2026-11-12T08:00:00.000Z', '2026-11-12T09:00:00.000Z')""").update();

        assertThat(thresholds.assignMissingDefaults()).isEqualTo(1);
        assertThat(thresholds.assignMissingDefaults()).isZero();

        assertThat(current(DEVICE).limits()).isEqualTo(new ThresholdLimits(-25, -18, 35, 80));
        assertThat(jdbc.sql("SELECT count(*) FROM threshold_settings").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void approvalIsRefusedWhenTheLimitsChangedSinceTheRequest() {
        deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat", "RAW-MEAT", "RAW-MEAT");
        long requestId = thresholds.submit(operator, DEVICE, CUSTOM).request().id();
        // A newer version that bypassed the usual supersede, e.g. written by another code path.
        long session = thresholds.getDeviceThreshold(DEVICE).session().id();
        jdbc.sql("""
                INSERT INTO threshold_settings (session_id, lower_c, upper_c, lower_humidity_pct, upper_humidity_pct,
                                                effective_from, changed_by)
                VALUES (?, 0, 3, 55, 80, '2026-11-12T08:00:00.000Z', ?)""").params(session, admin).update();

        assertThatThrownBy(() -> thresholds.approve(admin, requestId, null))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("THRESHOLD_REQUEST_STALE"));
        assertThat(requests.findById(requestId).orElseThrow().pending()).isTrue();
    }

    @Test
    void aDeviceWithoutAnActiveSessionCannotBeChanged() {
        long session = deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat", "RAW-MEAT", "RAW-MEAT").id();
        jdbc.sql("UPDATE storage_sessions SET ended_at = started_at WHERE id = ?").param(session).update();

        assertThatThrownBy(() -> thresholds.submit(admin, DEVICE, CUSTOM))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NO_ACTIVE_SESSION"));
        assertThat(thresholds.getDeviceThreshold(DEVICE).current()).isNull();
    }

    @Test
    void submittingTheCurrentLimitsWritesNothing() {
        deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat", "RAW-MEAT", "RAW-MEAT");
        long auditCount = auditEvents.findRecent(100).size();

        assertThat(thresholds.submit(admin, DEVICE, RAW_MEAT).outcome()).isEqualTo(SubmissionOutcome.UNCHANGED);
        assertThat(thresholds.submit(operator, DEVICE, RAW_MEAT).outcome()).isEqualTo(SubmissionOutcome.UNCHANGED);

        assertThat(jdbc.sql("SELECT count(*) FROM threshold_settings").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM threshold_change_requests").query(Long.class).single()).isZero();
        assertThat(auditEvents.findRecent(100)).hasSize((int) auditCount);
    }

    private ThresholdSetting current(String deviceId) {
        return thresholds.getDeviceThreshold(deviceId).current();
    }
}
