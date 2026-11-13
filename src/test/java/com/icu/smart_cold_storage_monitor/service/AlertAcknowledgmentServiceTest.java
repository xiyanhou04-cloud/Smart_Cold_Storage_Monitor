package com.icu.smart_cold_storage_monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertResolveReason;
import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import com.icu.smart_cold_storage_monitor.model.ReadingMetric;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.service.AlertAcknowledgmentService.AcknowledgmentOutcome;
import com.icu.smart_cold_storage_monitor.service.AlertAcknowledgmentService.AcknowledgmentResult;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingSubmission;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import com.icu.smart_cold_storage_monitor.service.exception.NotFoundException;
import com.icu.smart_cold_storage_monitor.service.exception.OperationNotPermittedException;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Alert acknowledgment (FR-06) and its audit event (FR-07) against a temporary SQLite database. */
class AlertAcknowledgmentServiceTest extends SqliteIntegrationTest {

    static final String DEVICE = "ESP32-SIM-001";
    static final String PASSWORD = "correct-horse-1";

    @Autowired
    AlertAcknowledgmentService acknowledgments;

    @Autowired
    ReadingIngestionService ingestion;

    @Autowired
    DeviceSessionService deviceSessions;

    @Autowired
    UserAccountService accounts;

    @Autowired
    AlertRepository alerts;

    @Autowired
    AuditEventRepository auditEvents;

    @MockitoSpyBean
    AuditService audit;

    long admin;
    long operator;
    long otherOperator;
    long session;
    Instant base;
    int step;

    @BeforeEach
    void usersAndAnAlert() {
        admin = accounts.createUser("admin1", PASSWORD, Role.ADMIN, null).user().id();
        operator = accounts.createUser("operator1", PASSWORD, Role.OPERATOR, null).user().id();
        otherOperator = accounts.createUser("operator2", PASSWORD, Role.OPERATOR, null).user().id();
        // RAW-MEAT default: 0 to 4 °C, 55 to 80 %RH.
        session = deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat", "RAW-MEAT", "RAW-MEAT").id();
        base = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        step = 0;
    }

    @Test
    void acknowledgmentRecordsUserTimeAndNoteAndKeepsTheAlertActive() {
        long alert = openAlert();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        AcknowledgmentResult result = acknowledgments.acknowledge(operator, alert, "  Closed the door.  ");

        assertThat(result.outcome()).isEqualTo(AcknowledgmentOutcome.ACKNOWLEDGED);
        Alert acknowledged = result.alert();
        assertThat(acknowledged.acknowledgedBy()).isEqualTo(operator);
        assertThat(acknowledged.acknowledgedByUsername()).isEqualTo("operator1");
        assertThat(acknowledged.acknowledgedAt()).isBetween(before, Instant.now());
        assertThat(acknowledged.note()).isEqualTo("Closed the door.");
        assertThat(acknowledged.active()).isTrue();
        assertThat(alerts.findById(alert).orElseThrow()).isEqualTo(acknowledged);
    }

    @Test
    void theFirstAcknowledgmentWritesOneAuditEventInTheSameTransaction() {
        long alert = openAlert();

        acknowledgments.acknowledge(operator, alert, "Closed the door.");

        List<AuditEvent> events = acknowledgmentEvents();
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.actorId()).isEqualTo(operator);
        assertThat(event.result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(event.targetType()).isEqualTo("ALERT");
        assertThat(event.targetId()).isEqualTo(String.valueOf(alert));
        assertThat(event.occurredAt()).isNotNull();
        assertThat(event.details()).contains("device=" + DEVICE, "kind=THRESHOLD", "severity=HIGH",
                "metric=TEMPERATURE ABOVE", "alertActive=true", "noteLength=16");
        assertThat(event.details()).doesNotContain("Closed the door");
    }

    @Test
    void aRepeatedAcknowledgmentByAnyoneReturnsTheFirstAndWritesNoAuditEvent() {
        long alert = openAlert();
        Alert first = acknowledgments.acknowledge(operator, alert, "First").alert();

        AcknowledgmentResult same = acknowledgments.acknowledge(operator, alert, "First");
        AcknowledgmentResult other = acknowledgments.acknowledge(otherOperator, alert, "Different note");
        AcknowledgmentResult byAdmin = acknowledgments.acknowledge(admin, alert, null);

        assertThat(List.of(same, other, byAdmin)).allSatisfy(result -> {
            assertThat(result.outcome()).isEqualTo(AcknowledgmentOutcome.ALREADY_ACKNOWLEDGED);
            assertThat(result.alert()).isEqualTo(first);
        });
        assertThat(acknowledgmentEvents()).hasSize(1);
    }

    @Test
    void aResolvedAlertCanBeAcknowledged() {
        long alert = openAlert();
        ingest(2.0);

        AcknowledgmentResult result = acknowledgments.acknowledge(admin, alert, null);

        assertThat(result.outcome()).isEqualTo(AcknowledgmentOutcome.ACKNOWLEDGED);
        assertThat(result.alert().active()).isFalse();
        assertThat(result.alert().acknowledgedByUsername()).isEqualTo("admin1");
        assertThat(acknowledgmentEvents().get(0).details()).contains("alertActive=false", "noteLength=0");
    }

    @Test
    void theExcursionContinuesAndResolvesAfterAcknowledgmentAndALaterOneNeedsItsOwn() {
        long alert = openAlert();
        acknowledgments.acknowledge(operator, alert, "Watching it");

        ingest(6.0);
        Alert continuing = alerts.findById(alert).orElseThrow();
        assertThat(continuing.active()).isTrue();
        assertThat(continuing.peakValue()).isEqualTo(6.0);
        assertThat(continuing.note()).isEqualTo("Watching it");

        ingest(2.0);
        ingest(5.0);
        List<Alert> all = alerts.findBySession(session);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).resolveReason()).isEqualTo(AlertResolveReason.RECOVERED);
        assertThat(all.get(0).acknowledgedBy()).isEqualTo(operator);
        assertThat(all.get(1).active()).isTrue();
        assertThat(all.get(1).acknowledged()).isFalse();
    }

    @Test
    void blankNoteIsStoredAsNoNoteAndTheLimitIs500CharactersAfterStripping() {
        long first = openAlert();
        assertThat(acknowledgments.acknowledge(operator, first, "   ").alert().note()).isNull();

        long second = openAlertOnHumidity();
        assertThatThrownBy(() -> acknowledgments.acknowledge(operator, second, "x".repeat(501)))
                .isInstanceOf(InvalidInputException.class);
        assertThat(alerts.findById(second).orElseThrow().acknowledged()).isFalse();
        assertThat(acknowledgments.acknowledge(operator, second, " " + "x".repeat(500) + " ").alert().note())
                .hasSize(500);
    }

    @Test
    void unknownAlertIs404() {
        assertThatThrownBy(() -> acknowledgments.acknowledge(operator, 999_999, null))
                .isInstanceOf(NotFoundException.class);
        assertThat(acknowledgmentEvents()).isEmpty();
    }

    @Test
    void aDeactivatedUserCannotAcknowledge() {
        long alert = openAlert();
        accounts.deactivateUser(admin, operator);

        assertThatThrownBy(() -> acknowledgments.acknowledge(operator, alert, null))
                .isInstanceOf(OperationNotPermittedException.class);
        assertThat(alerts.findById(alert).orElseThrow().acknowledged()).isFalse();
    }

    @Test
    void anAuditFailureRollsTheAcknowledgmentBack() {
        long alert = openAlert();
        RuntimeException failure = new IllegalStateException("audit storage down");
        doThrow(failure).when(audit).recordAlertAcknowledged(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> acknowledgments.acknowledge(operator, alert, "Closed the door."))
                .isSameAs(failure);

        Alert unchanged = alerts.findById(alert).orElseThrow();
        assertThat(unchanged.acknowledged()).isFalse();
        assertThat(unchanged.acknowledgedBy()).isNull();
        assertThat(unchanged.note()).isNull();
        assertThat(acknowledgmentEvents()).isEmpty();
    }

    /** Opens a temperature alert (above 4 °C) and returns its id. */
    private long openAlert() {
        ingest(5.0);
        return alerts.findActiveThresholdAlerts(session).getFirst().id();
    }

    private long openAlertOnHumidity() {
        ingestion.ingest(DEVICE, new ReadingSubmission(DEVICE, session, base.plusSeconds(step++), 2.0, 90.0));
        return alerts.findActiveThresholdAlerts(session).stream()
                .filter(a -> a.metric() == ReadingMetric.HUMIDITY)
                .findFirst().orElseThrow().id();
    }

    private void ingest(double temperatureC) {
        ingestion.ingest(DEVICE, new ReadingSubmission(DEVICE, session, base.plusSeconds(step++), temperatureC, 70.0));
    }

    private List<AuditEvent> acknowledgmentEvents() {
        return auditEvents.findRecent(500).stream()
                .filter(e -> e.eventType() == AuditEventType.ALERT_ACKNOWLEDGED)
                .toList();
    }
}
