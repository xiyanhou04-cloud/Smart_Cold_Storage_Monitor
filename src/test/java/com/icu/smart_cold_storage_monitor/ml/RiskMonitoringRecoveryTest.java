package com.icu.smart_cold_storage_monitor.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.icu.smart_cold_storage_monitor.SmartColdStorageMonitorApplication;
import com.icu.smart_cold_storage_monitor.controller.ReadingQueryController;
import com.icu.smart_cold_storage_monitor.repository.SqlTimestamps;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingSubmission;
import com.icu.smart_cold_storage_monitor.service.exception.ConflictException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class RiskMonitoringRecoveryTest {
    private static final Instant START = Instant.parse("2026-11-10T00:00:00Z");
    private static final String DEVICE = "recovery-sensor";

    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();

    @Test
    void orderlyRestartSeparatesInterruptionFromSensorGapAndRecoversModel() throws Exception {
        exerciseRestart(true, "MONITORING_INTERRUPTED", "ORDERLY_MONITORING_INTERRUPTION");
    }

    @Test
    void restartWithoutDurableStopRemainsUnconfirmedAndRecoversModel() throws Exception {
        exerciseRestart(false, "MONITORING_INTERRUPTION_UNKNOWN", "UNCONFIRMED_MONITORING_INTERRUPTION");
    }

    @Test
    void distinguishesThirtySecondsFromRunningGapAndKeepsHistoryAfterRecovery() throws Exception {
        try (var app = start()) {
            long session = session(app);
            ingest(app, session, 0);
            long boundary = ingest(app, session, 30);
            assertLatest(app, "MODEL", "OK", false, "NONE", "NONE");
            assertExposure(app, boundary, 30, 30);
            long gap = ingest(app, session, 61);
            assertLatest(app, "RULES", "INCOMPLETE_HISTORY", true, "RUNNING_DATA_GAP", "RUNNING_DATA_GAP");
            assertExposure(app, gap, 30, 61);
            long recovered = ingest(app, session, 66);
            assertLatest(app, "MODEL", "OK", true, "NONE", "RUNNING_DATA_GAP");
            assertExposure(app, recovered, 35, 66);
            assertThat(jdbc(app).sql("SELECT quality_flag FROM predictions WHERE reading_id=?")
                    .param(gap).query(String.class).single()).isEqualTo("INCOMPLETE_HISTORY");
            assertSingleThresholdAlert(app);
        }
    }

    @Test
    void legacySnapshotRetainsOriginalQualityAndStopsForcingFallbackOnNewGoodIntervals() throws Exception {
        try (var app = start()) {
            long session = session(app);
            ingest(app, session, 0);
            long gap = ingest(app, session, 35);
            jdbc(app).sql("""
                    UPDATE predictions SET feature_snapshot = json_remove(feature_snapshot,
                       '$.features.currentIssue', '$.features.lastGapReason') WHERE reading_id = ?
                    """).param(gap).update();
            assertLatest(app, "RULES", "INCOMPLETE_HISTORY", true, null, "LEGACY_UNATTRIBUTED");
            ingest(app, session, 40);
            assertLatest(app, "MODEL", "OK", true, "NONE", "LEGACY_UNATTRIBUTED");
            assertThat(jdbc(app).sql("SELECT quality_flag FROM predictions WHERE reading_id=?")
                    .param(gap).query(String.class).single()).isEqualTo("INCOMPLETE_HISTORY");
        }
    }

    @Test
    void bufferedSamplesUseMeasurementOrderEvenWithFastAndSlowDeviceTimestamps() throws Exception {
        try (var app = start()) {
            long session = session(app);
            ingest(app, session, 0);
            long fast = ingest(app, session, 5, 1);
            assertExposure(app, fast, 5, 5);
            ingest(app, session, 10, 45);
            long buffered = ingest(app, session, 15, 45);
            assertExposure(app, buffered, 15, 15);
            assertLatest(app, "MODEL", "OK", false, "NONE", "NONE");
            assertThrows(ConflictException.class, () -> ingest(app, session, 12, 45));
        }
    }

    @Test
    void backwardsServerClockMakesAttributionUnknownWithoutCountingExposure() throws Exception {
        try (var app = start()) {
            long session = session(app);
            ingest(app, session, 0);
            ingest(app, session, 20, 25);
            long ambiguous = ingest(app, session, 25, 15);
            assertExposure(app, ambiguous, 20, 25);
            assertLatest(app, "RULES", "MONITORING_INTERRUPTION_UNKNOWN", true,
                    "UNCONFIRMED_MONITORING_INTERRUPTION", "UNCONFIRMED_MONITORING_INTERRUPTION");
        }
    }

    @Test
    void impossibleCheckpointExposureIsRebuiltFromReadings() throws Exception {
        try (var app = start()) {
            long session = session(app);
            ingest(app, session, 0);
            long checkpoint = ingest(app, session, 5);
            jdbc(app).sql("""
                    UPDATE predictions SET feature_snapshot=json_set(
                        feature_snapshot, '$.features.observedAboveUpperSeconds', 900)
                    WHERE reading_id=?
                    """).param(checkpoint).update();
            long next = ingest(app, session, 10);
            assertExposure(app, next, 10, 10);
        }
    }

    @Test
    void recoveredModelCanUnderestimateAnUnobservedSustainedExposureAndDoesNotCloseAlert() throws Exception {
        try (var app = start()) {
            long session = session(app);
            ingest(app, session, 0, 0, 4.0032);
            long gap = ingest(app, session, 700, 700, 4.0032);
            assertExposure(app, gap, 0, 700);
            long resumed = ingest(app, session, 705, 705, 4.0032);
            assertLatest(app, "MODEL", "OK", true, "NONE", "RUNNING_DATA_GAP");
            assertExposure(app, resumed, 5, 705);
            var current = app.getBean(ReadingQueryController.class).latest(DEVICE).get(DEVICE).prediction();
            assertThat(current.riskLevel()).isEqualTo("MEDIUM");
            var rule = new RiskRules(120, 600);
            var hypothetical = new RiskFeatures.Snapshot(4.0032, 70, 705, 705, "RAW_MEAT", false);
            var bounds = new com.icu.smart_cold_storage_monitor.model.ThresholdLimits(0, 4, 55, 80);
            assertThat(rule.predict(hypothetical, bounds).name()).isEqualTo("HIGH");
            System.out.printf("R4 resumed=%s, observedExposure=5s, hypotheticalContinuousExposure=705s%n", current.riskLevel());
            long secondGap = ingest(app, session, 750, 750, 4.0032);
            assertExposure(app, secondGap, 5, 750);
            assertLatest(app, "RULES", "INCOMPLETE_HISTORY", true, "RUNNING_DATA_GAP", "RUNNING_DATA_GAP");
            assertSingleThresholdAlert(app);
            assertThat(jdbc(app).sql("SELECT count(*) FROM alerts WHERE kind = 'THRESHOLD' AND resolved_at IS NULL")
                    .query(Long.class).single()).isOne();
            // The gap predictions at 700 s and 750 s are rules fallbacks: only the model's MEDIUM at 705 s is
            // an active risk alert.
            assertThat(jdbc(app).sql("""
                    SELECT severity || ' ' || source || ' ' || opened_at || ' ' || last_seen_at FROM alerts
                    WHERE kind = 'RISK' AND resolved_at IS NULL""").query(String.class).list())
                    .containsExactly("MEDIUM MODEL " + SqlTimestamps.format(START.plusSeconds(705)) + " "
                            + SqlTimestamps.format(START.plusSeconds(705)));
        }
    }

    private void exerciseRestart(boolean orderly, String quality, String reason) throws Exception {
        long session;
        long existingGap;
        try (var app = start()) {
            session = session(app);
            ingest(app, session, 0);
            existingGap = ingest(app, session, 35);
            assertLatest(app, "RULES", "INCOMPLETE_HISTORY", true, "RUNNING_DATA_GAP", "RUNNING_DATA_GAP");
            ingest(app, session, 40);
            assertLatest(app, "MODEL", "OK", true, "NONE", "RUNNING_DATA_GAP");
            clock.seconds(42);
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("risk.db"));
             var statement = connection.createStatement()) {
            if (!orderly) {
                // Reproduce the durable state a process killed before its shutdown hook leaves.
                assertThat(statement.executeUpdate("UPDATE risk_monitoring_runs SET stopped_at=NULL")).isEqualTo(1);
            } else {
                try (var saved = statement.executeQuery("SELECT stopped_at FROM risk_monitoring_runs")) {
                    assertThat(saved.next()).isTrue();
                    assertThat(Instant.parse(saved.getString(1))).isEqualTo(START.plusSeconds(42));
                }
            }
        }
        clock.seconds(100);
        try (var app = start()) {
            long first = ingest(app, session, 105);
            assertLatest(app, "RULES", quality, true, reason, reason);
            assertExposure(app, first, 5, 105);
            assertThat(ingest(app, session, 105)).isEqualTo(first);
            long recovered = ingest(app, session, 110);
            assertExposure(app, recovered, 10, 110);
            assertLatest(app, "MODEL", "OK", true, "NONE", reason);
            assertThat(jdbc(app).sql("SELECT count(*) FROM predictions WHERE reading_id=?")
                    .param(first).query(Long.class).single()).isOne();
            assertThat(jdbc(app).sql("""
                    SELECT json_extract(feature_snapshot,'$.features.currentIssue') FROM predictions WHERE reading_id=?
                    """).param(existingGap).query(String.class).single()).isEqualTo("RUNNING_DATA_GAP");
            assertSingleThresholdAlert(app);
        }
        clock.seconds(160);
        try (var app = start()) {
            long afterSecondStop = ingest(app, session, 165);
            assertExposure(app, afterSecondStop, 10, 165);
            assertLatest(app, "RULES", "MONITORING_INTERRUPTED", true,
                    "ORDERLY_MONITORING_INTERRUPTION", "ORDERLY_MONITORING_INTERRUPTION");
            assertSingleThresholdAlert(app);
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(SmartColdStorageMonitorApplication.class)
                .initializers(context -> ((GenericApplicationContext) context).registerBean("recoveryClock",
                        Clock.class, () -> clock, definition -> definition.setPrimary(true)))
                .run("--server.port=0", "--app.simulator.enabled=false",
                        "--app.database.path=" + directory.resolve("risk.db"));
    }

    private long session(ConfigurableApplicationContext app) {
        return app.getBean(DeviceSessionService.class).ensureDeviceWithActiveSession(
                DEVICE, "recovery sensor", "RAW-MEAT", "RAW-MEAT").id();
    }

    private long ingest(ConfigurableApplicationContext app, long session, long seconds) throws Exception {
        return ingest(app, session, seconds, seconds);
    }

    private long ingest(ConfigurableApplicationContext app, long session, long measured, long received) throws Exception {
        return ingest(app, session, measured, received, 5);
    }

    private long ingest(ConfigurableApplicationContext app, long session, long measured, long received,
                        double temperature) throws Exception {
        clock.seconds(received);
        long id = app.getBean(ReadingIngestionService.class)
                .ingest(DEVICE, new ReadingSubmission(DEVICE, session, START.plusSeconds(measured), temperature, 70)).reading().id();
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc(app)
                .sql("SELECT EXISTS (SELECT 1 FROM predictions WHERE reading_id=?)").param(id)
                .query(Boolean.class).single());
        return id;
    }

    private static void assertLatest(ConfigurableApplicationContext app, String source, String quality,
                                     boolean incomplete, String current, String historical) {
        var prediction = app.getBean(ReadingQueryController.class).latest(DEVICE).get(DEVICE).prediction();
        assertThat(prediction.source()).isEqualTo(source);
        assertThat(prediction.qualityFlag()).isEqualTo(quality);
        assertThat(prediction.historicalExposureIncomplete()).isEqualTo(incomplete);
        assertThat(prediction.currentGapReason()).isEqualTo(current);
        assertThat(prediction.lastGapReason()).isEqualTo(historical);
    }

    private static void assertExposure(ConfigurableApplicationContext app, long id, double exposure, double elapsed) {
        String snapshot = jdbc(app).sql("SELECT feature_snapshot FROM predictions WHERE reading_id=?")
                .param(id).query(String.class).single();
        var features = app.getBean(ObjectMapper.class).readTree(snapshot).path("features");
        assertThat(features.path("observedAboveUpperSeconds").asDouble()).isEqualTo(exposure);
        assertThat(features.path("elapsedStorageSeconds").asDouble()).isEqualTo(elapsed);
    }

    private static void assertSingleThresholdAlert(ConfigurableApplicationContext app) {
        // Risk alerts are written alongside; this checks that the threshold alert is never repeated.
        assertThat(jdbc(app).sql("SELECT kind FROM alerts WHERE kind = 'THRESHOLD' ORDER BY id")
                .query(String.class).list()).containsExactly("THRESHOLD");
    }

    private static JdbcClient jdbc(ConfigurableApplicationContext app) {
        return app.getBean(JdbcClient.class);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = START;

        void seconds(long seconds) {
            now = START.plusSeconds(seconds);
        }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
