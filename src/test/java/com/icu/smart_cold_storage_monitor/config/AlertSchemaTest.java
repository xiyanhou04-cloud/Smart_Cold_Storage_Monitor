package com.icu.smart_cold_storage_monitor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** Constraints of the {@code alerts} table shared by threshold (FR-04) and risk (FR-05) alerts. */
class AlertSchemaTest extends SqliteIntegrationTest {

    static final String DEVICE = "ESP32-SIM-001";
    static final String OTHER_DEVICE = "ESP32-SIM-002";
    static final String T0 = "2026-11-12T08:00:00.000Z";
    static final String T1 = "2026-11-12T08:00:05.000Z";
    static final String T2 = "2026-11-12T08:00:10.000Z";

    long session;
    long otherSession;
    long threshold;
    long otherThreshold;

    @BeforeEach
    void sessionsWithThresholds() {
        jdbc.sql("INSERT INTO devices (id, name) VALUES (?, 'A'), (?, 'B')").params(DEVICE, OTHER_DEVICE).update();
        session = session(DEVICE);
        otherSession = session(OTHER_DEVICE);
        threshold = threshold(session);
        otherThreshold = threshold(otherSession);
    }

    @Test
    void schemaCreatesTheAlertsTable() {
        assertThat(jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'table'").query(String.class).list())
                .contains("alerts");
    }

    @Test
    void storesAThresholdAndARiskAlert() {
        assertThatCode(() -> thresholdAlert(session, DEVICE, threshold, "TEMPERATURE")).doesNotThrowAnyException();
        assertThatCode(() -> riskAlert(session, DEVICE)).doesNotThrowAnyException();
    }

    @Test
    void oneActiveThresholdAlertPerSessionAndMetric() {
        long first = thresholdAlert(session, DEVICE, threshold, "TEMPERATURE");

        assertThatThrownBy(() -> thresholdAlert(session, DEVICE, threshold, "TEMPERATURE"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatCode(() -> thresholdAlert(session, DEVICE, threshold, "HUMIDITY")).doesNotThrowAnyException();
        assertThatCode(() -> thresholdAlert(otherSession, OTHER_DEVICE, otherThreshold, "TEMPERATURE"))
                .doesNotThrowAnyException();

        resolve(first, T2, "RECOVERED");
        assertThatCode(() -> thresholdAlert(session, DEVICE, threshold, "TEMPERATURE")).doesNotThrowAnyException();
    }

    @Test
    void oneActiveRiskAlertPerSessionAlongsideThresholdAlerts() {
        thresholdAlert(session, DEVICE, threshold, "TEMPERATURE");
        long first = riskAlert(session, DEVICE);

        assertThatThrownBy(() -> riskAlert(session, DEVICE)).isInstanceOf(DuplicateKeyException.class);

        resolve(first, T2, "SUPERSEDED");
        assertThatCode(() -> riskAlert(session, DEVICE)).doesNotThrowAnyException();
    }

    @Test
    void thresholdAlertNeedsItsThresholdFieldsAndNoModelFields() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at, metric,
                                    direction, opening_threshold_id, opening_value, last_value, peak_value)
                VALUES (?, ?, 'THRESHOLD', 'HIGH', 'THRESHOLD', ?, ?, 'TEMPERATURE', 'ABOVE', ?, 5, 5, 5)""")
                .params(session, DEVICE, T1, T1, threshold).update())
                .as("missing limit_value")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at, metric,
                                    direction, opening_threshold_id, limit_value, opening_value, last_value,
                                    peak_value)
                VALUES (?, ?, 'THRESHOLD', 'HIGH', 'MODEL', ?, ?, 'TEMPERATURE', 'ABOVE', ?, 4, 5, 5, 5)""")
                .params(session, DEVICE, T1, T1, threshold).update())
                .as("source other than THRESHOLD")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at, metric,
                                    direction, opening_threshold_id, limit_value, opening_value, last_value,
                                    peak_value, model_or_rule_version)
                VALUES (?, ?, 'THRESHOLD', 'HIGH', 'THRESHOLD', ?, ?, 'TEMPERATURE', 'ABOVE', ?, 4, 5, 5, 5, 'rf-1')""")
                .params(session, DEVICE, T1, T1, threshold).update())
                .as("model version on a threshold alert")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void riskAlertNeedsAModelVersionAndNoThresholdFields() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at)
                VALUES (?, ?, 'RISK', 'MEDIUM', 'RULES', ?, ?)""").params(session, DEVICE, T1, T1).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at,
                                    model_or_rule_version, metric)
                VALUES (?, ?, 'RISK', 'MEDIUM', 'RULES', ?, ?, 'rules-1', 'TEMPERATURE')""")
                .params(session, DEVICE, T1, T1).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at,
                                    model_or_rule_version)
                VALUES (?, ?, 'RISK', 'MEDIUM', 'THRESHOLD', ?, ?, 'rules-1')""")
                .params(session, DEVICE, T1, T1).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void severityIsMediumOrHigh() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at,
                                    model_or_rule_version)
                VALUES (?, ?, 'RISK', 'LOW', 'MODEL', ?, ?, 'rf-1')""").params(session, DEVICE, T1, T1).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resolutionNeedsBothTimeAndReasonAndCannotPrecedeLastSeen() {
        long id = thresholdAlert(session, DEVICE, threshold, "TEMPERATURE");

        assertThatThrownBy(() -> jdbc.sql("UPDATE alerts SET resolved_at = ? WHERE id = ?").params(T2, id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> resolve(id, T0, "RECOVERED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> resolve(id, T2, "FIXED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> resolve(id, T1, "RECOVERED")).doesNotThrowAnyException();
    }

    @Test
    void noteNeedsAnAcknowledgmentAndIsBounded() {
        long user = jdbc.sql("""
                INSERT INTO users (username, password_hash, role, created_at) VALUES ('op1', 'x', 'OPERATOR', ?)
                RETURNING id""").param(T0).query(Long.class).single();
        long id = thresholdAlert(session, DEVICE, threshold, "TEMPERATURE");

        assertThatThrownBy(() -> jdbc.sql("UPDATE alerts SET note = 'checked' WHERE id = ?").param(id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE alerts SET acknowledged_at = ? WHERE id = ?").params(T2, id)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> acknowledge(id, user, "x".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> acknowledge(id, user, "x".repeat(500))).doesNotThrowAnyException();
    }

    @Test
    void sessionMustBelongToTheDeviceAndThresholdToTheSession() {
        assertThatThrownBy(() -> thresholdAlert(session, OTHER_DEVICE, threshold, "TEMPERATURE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> thresholdAlert(session, DEVICE, otherThreshold, "TEMPERATURE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void alertsDoNotReferenceReadingsSoClearingReadingsKeepsThem() {
        jdbc.sql("""
                INSERT INTO readings (device_id, session_id, measured_at, received_at, temperature_c, humidity_pct,
                                      threshold_id)
                VALUES (?, ?, ?, ?, 9, 70, ?)""").params(DEVICE, session, T1, T1, threshold).update();
        thresholdAlert(session, DEVICE, threshold, "TEMPERATURE");

        jdbc.sql("DELETE FROM readings").update();

        assertThat(jdbc.sql("SELECT count(*) FROM alerts").query(Long.class).single()).isEqualTo(1);
    }

    private long session(String device) {
        return jdbc.sql("""
                INSERT INTO storage_sessions (device_id, product_type, started_at) VALUES (?, 'RAW-MEAT', ?)
                RETURNING id""").params(device, T0).query(Long.class).single();
    }

    private long threshold(long sessionId) {
        return jdbc.sql("""
                INSERT INTO threshold_settings (session_id, lower_c, upper_c, lower_humidity_pct, upper_humidity_pct,
                                                effective_from)
                VALUES (?, 0, 4, 55, 80, ?)
                RETURNING id""").params(sessionId, T0).query(Long.class).single();
    }

    private long thresholdAlert(long sessionId, String device, long thresholdId, String metric) {
        return jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at, metric,
                                    direction, opening_threshold_id, limit_value, opening_value, last_value,
                                    peak_value)
                VALUES (?, ?, 'THRESHOLD', 'HIGH', 'THRESHOLD', ?, ?, ?, 'ABOVE', ?, 4, 5, 5, 5)
                RETURNING id""").params(sessionId, device, T1, T1, metric, thresholdId).query(Long.class).single();
    }

    private long riskAlert(long sessionId, String device) {
        return jdbc.sql("""
                INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at,
                                    model_or_rule_version)
                VALUES (?, ?, 'RISK', 'MEDIUM', 'RULES', ?, ?, 'rules-1')
                RETURNING id""").params(sessionId, device, T1, T1).query(Long.class).single();
    }

    private void resolve(long id, String resolvedAt, String reason) {
        jdbc.sql("UPDATE alerts SET resolved_at = ?, resolve_reason = ? WHERE id = ?").params(resolvedAt, reason, id)
                .update();
    }

    private void acknowledge(long id, long user, String note) {
        jdbc.sql("UPDATE alerts SET acknowledged_by = ?, acknowledged_at = ?, note = ? WHERE id = ?")
                .params(user, T2, note, id).update();
    }
}
