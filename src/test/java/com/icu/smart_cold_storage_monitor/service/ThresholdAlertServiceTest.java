package com.icu.smart_cold_storage_monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertDirection;
import com.icu.smart_cold_storage_monitor.model.AlertKind;
import com.icu.smart_cold_storage_monitor.model.AlertResolveReason;
import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.ReadingMetric;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository;
import com.icu.smart_cold_storage_monitor.repository.ThresholdSettingRepository;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.IngestionResult;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingSubmission;
import com.icu.smart_cold_storage_monitor.service.exception.ConflictException;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/** The threshold alert state machine (FR-04), driven through reading ingestion against a temporary SQLite file. */
class ThresholdAlertServiceTest extends SqliteIntegrationTest {

    static final String DEVICE = "ESP32-SIM-001";
    /** RAW-MEAT default from application.yaml: 0 to 4 °C, 55 to 80 %RH. */
    static final double OK_T = 2.0;
    static final double OK_RH = 70.0;

    @Autowired
    ReadingIngestionService ingestion;

    @Autowired
    ThresholdAlertService thresholdAlerts;

    @Autowired
    DeviceSessionService deviceSessions;

    @Autowired
    AlertRepository alerts;

    @Autowired
    ThresholdSettingRepository thresholds;

    @Autowired
    ReadingRetentionService retention;

    long session;
    Instant base;
    int step;

    @BeforeEach
    void deviceWithSession() {
        session = deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat", "RAW-MEAT", "RAW-MEAT").id();
        base = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        step = 0;
    }

    @Test
    void inRangeValuesIncludingTheLimitsOpenNothing() {
        ingest(OK_T, OK_RH);
        ingest(0.0, 55.0);
        ingest(4.0, 80.0);

        assertThat(alerts.findBySession(session)).isEmpty();
    }

    @Test
    void aValueAboveTheUpperLimitOpensAHighThresholdAlert() {
        Reading reading = ingest(4.5, OK_RH);

        Alert alert = single();
        assertThat(alert.kind()).isEqualTo(AlertKind.THRESHOLD);
        assertThat(alert.severity()).isEqualTo(RiskLevel.HIGH);
        assertThat(alert.source()).isEqualTo(AlertSource.THRESHOLD);
        assertThat(alert.deviceId()).isEqualTo(DEVICE);
        assertThat(alert.metric()).isEqualTo(ReadingMetric.TEMPERATURE);
        assertThat(alert.direction()).isEqualTo(AlertDirection.ABOVE);
        assertThat(alert.openingThresholdId()).isEqualTo(reading.thresholdId());
        assertThat(alert.limitValue()).isEqualTo(4.0);
        assertThat(alert.openingValue()).isEqualTo(4.5);
        assertThat(alert.lastValue()).isEqualTo(4.5);
        assertThat(alert.peakValue()).isEqualTo(4.5);
        assertThat(alert.openedAt()).isEqualTo(reading.measuredAt());
        assertThat(alert.lastSeenAt()).isEqualTo(reading.measuredAt());
        assertThat(alert.active()).isTrue();
        assertThat(alert.acknowledgedAt()).isNull();
        assertThat(alert.modelOrRuleVersion()).isNull();
    }

    @Test
    void continuedExcursionUpdatesTheSameAlertAndKeepsTheHighestValue() {
        ingest(4.5, OK_RH);
        ingest(6.0, OK_RH);
        Reading last = ingest(5.0, OK_RH);

        Alert alert = single();
        assertThat(alert.openingValue()).isEqualTo(4.5);
        assertThat(alert.lastValue()).isEqualTo(5.0);
        assertThat(alert.peakValue()).isEqualTo(6.0);
        assertThat(alert.lastSeenAt()).isEqualTo(last.measuredAt());
        assertThat(alert.active()).isTrue();
    }

    @Test
    void belowTheLowerLimitKeepsTheLowestValue() {
        ingest(-0.5, OK_RH);
        ingest(-2.0, OK_RH);
        ingest(-1.0, OK_RH);

        Alert alert = single();
        assertThat(alert.direction()).isEqualTo(AlertDirection.BELOW);
        assertThat(alert.limitValue()).isEqualTo(0.0);
        assertThat(alert.peakValue()).isEqualTo(-2.0);
        assertThat(alert.lastValue()).isEqualTo(-1.0);
    }

    @Test
    void theNextInRangeReadingResolvesAndALaterExcursionOpensANewAlert() {
        ingest(5.0, OK_RH);
        Reading recovered = ingest(4.0, OK_RH);
        ingest(OK_T, OK_RH);
        ingest(5.5, OK_RH);

        List<Alert> all = alerts.findBySession(session);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).resolvedAt()).isEqualTo(recovered.measuredAt());
        assertThat(all.get(0).resolveReason()).isEqualTo(AlertResolveReason.RECOVERED);
        assertThat(all.get(1).active()).isTrue();
        assertThat(all.get(1).openingValue()).isEqualTo(5.5);
    }

    @Test
    void aJumpAcrossTheRangeSupersedesTheAlertWithOneForTheNewDirection() {
        ingest(-1.0, OK_RH);
        Reading jump = ingest(5.0, OK_RH);

        List<Alert> all = alerts.findBySession(session);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).direction()).isEqualTo(AlertDirection.BELOW);
        assertThat(all.get(0).resolveReason()).isEqualTo(AlertResolveReason.SUPERSEDED);
        assertThat(all.get(0).resolvedAt()).isEqualTo(jump.measuredAt());
        assertThat(all.get(1).direction()).isEqualTo(AlertDirection.ABOVE);
        assertThat(all.get(1).openedAt()).isEqualTo(jump.measuredAt());
        assertThat(all.get(1).active()).isTrue();
    }

    @Test
    void temperatureAndHumidityHaveIndependentAlerts() {
        ingest(5.0, 85.0);
        ingest(5.0, OK_RH);

        List<Alert> all = alerts.findBySession(session);
        assertThat(all).extracting(Alert::metric)
                .containsExactly(ReadingMetric.TEMPERATURE, ReadingMetric.HUMIDITY);
        Alert temperature = all.get(0);
        Alert humidity = all.get(1);
        assertThat(temperature.active()).isTrue();
        assertThat(humidity.severity()).isEqualTo(RiskLevel.HIGH);
        assertThat(humidity.limitValue()).isEqualTo(80.0);
        assertThat(humidity.resolveReason()).isEqualTo(AlertResolveReason.RECOVERED);
    }

    @Test
    void anIdenticalRetryOrARejectedReadingChangesNoAlert() {
        Instant first = next();
        ingestAt(first, 5.0, OK_RH);
        Instant second = next();
        ingestAt(second, 6.0, OK_RH);
        Alert before = single();

        IngestionResult retry = ingestAt(second, 6.0, OK_RH);
        assertThat(retry.created()).isFalse();
        assertThatThrownBy(() -> ingestAt(first, OK_T, OK_RH)).isInstanceOf(ConflictException.class);

        assertThat(single()).isEqualTo(before);
    }

    @Test
    void aThresholdChangeAppliesToTheNextReadingAndKeepsTheOpeningVersion() {
        Reading opening = ingest(5.0, OK_RH);
        long widened = thresholds.insert(session, new ThresholdLimits(0, 8, 55, 80), Instant.now(), null);

        Reading next = ingest(5.0, OK_RH);

        Alert alert = single();
        assertThat(next.thresholdId()).isEqualTo(widened);
        assertThat(alert.resolveReason()).isEqualTo(AlertResolveReason.RECOVERED);
        assertThat(alert.openingThresholdId()).isEqualTo(opening.thresholdId());
        assertThat(alert.limitValue()).isEqualTo(4.0);
    }

    @Test
    void aSessionWithoutAThresholdVersionStoresReadingsWithoutAlerts() {
        jdbc.sql("INSERT INTO devices (id, name) VALUES ('ESP32-X', 'No limits')").update();
        long bare = jdbc.sql("""
                INSERT INTO storage_sessions (device_id, product_type, started_at) VALUES ('ESP32-X', 'X', ?)
                RETURNING id""").param("2026-01-01T00:00:00.000Z").query(Long.class).single();

        ingestion.ingest("ESP32-X", new ReadingSubmission("ESP32-X", bare, next(), 50.0, 99.0));

        assertThat(alerts.findBySession(bare)).isEmpty();
    }

    @Test
    void alertsOutliveClearingTheReadings() {
        ingest(5.0, OK_RH);

        retention.clearAllReadings();
        ingest(OK_T, OK_RH);

        Alert alert = single();
        assertThat(alert.resolveReason()).isEqualTo(AlertResolveReason.RECOVERED);
    }

    @Test
    void evaluationOutsideTheReadingTransactionIsRefused() {
        Reading reading = ingest(OK_T, OK_RH);

        assertThatThrownBy(() -> thresholdAlerts.evaluate(reading,
                thresholds.findById(reading.thresholdId()).orElseThrow()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Reading ingest(double temperatureC, double humidityPct) {
        return ingestAt(next(), temperatureC, humidityPct).reading();
    }

    private IngestionResult ingestAt(Instant timestamp, double temperatureC, double humidityPct) {
        return ingestion.ingest(DEVICE, new ReadingSubmission(DEVICE, session, timestamp, temperatureC, humidityPct));
    }

    private Instant next() {
        return base.plusSeconds(step++);
    }

    private Alert single() {
        List<Alert> all = alerts.findBySession(session);
        assertThat(all).hasSize(1);
        return all.get(0);
    }
}
