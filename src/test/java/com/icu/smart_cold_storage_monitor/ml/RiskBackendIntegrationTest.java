package com.icu.smart_cold_storage_monitor.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.repository.ThresholdSettingRepository;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingSubmission;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class RiskBackendIntegrationTest extends SqliteIntegrationTest {

    @Autowired ReadingIngestionService ingestion;
    @Autowired DeviceSessionService devices;
    @Autowired ThresholdSettingRepository thresholds;
    @Autowired UserAccountService accounts;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("riskExecutor") ThreadPoolTaskExecutor executor;

    @Test
    void modelPredictionUsesHistoricalVersionsAndLatestEndpointShowsFallbackForGap() throws Exception {
        var session = devices.ensureDeviceWithActiveSession("risk-device", "test sensor", "RAW-MEAT", "RAW-MEAT");
        Instant base = session.startedAt();
        long first = ingest(session.id(), base, 5);
        awaitPrediction(first);
        String original = snapshot(first);
        assertThat(column(first, "source")).isEqualTo("MODEL");

        accounts.createUser("risk-admin", "test-password-1", Role.ADMIN, null);
        var admin = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(admin).with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "risk-admin").param("password", "test-password-1")).andExpect(status().isOk());
        mvc.perform(put("/api/settings/thresholds/risk-device").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"temperatureLowerC":0,"temperatureUpperC":6,"humidityLowerPct":55,"humidityUpperPct":80}
                        """)).andExpect(status().isOk());
        long version = thresholds.findCurrentIdBySession(session.id()).orElseThrow();
        long second = ingest(session.id(), base.plusSeconds(5), 5);
        awaitPrediction(second);
        var secondSnapshot = mapper.readTree(snapshot(second));
        assertThat(secondSnapshot.path("thresholdId").asLong()).isEqualTo(version);
        assertThat(secondSnapshot.path("features").path("observedAboveUpperSeconds").asDouble()).isEqualTo(5);
        assertThat(snapshot(first)).isEqualTo(original);

        long gap = ingest(session.id(), base.plusSeconds(36), 5);
        awaitPrediction(gap);
        assertThat(column(gap, "source")).isEqualTo("RULES");
        assertThat(column(gap, "quality_flag")).isEqualTo("INCOMPLETE_HISTORY");
        assertThat(mapper.readTree(snapshot(gap)).path("features").path("observedAboveUpperSeconds").asDouble()).isEqualTo(5);
        mvc.perform(get("/api/readings/latest")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/readings/latest").param("deviceId", "risk-device").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$['risk-device'].reading.id").value(gap))
                .andExpect(jsonPath("$['risk-device'].prediction.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$['risk-device'].prediction.source").value("RULES"))
                .andExpect(jsonPath("$['risk-device'].prediction.predictedAt").isString())
                .andExpect(jsonPath("$['risk-device'].prediction.qualityFlag").value("INCOMPLETE_HISTORY"))
                .andExpect(jsonPath("$['risk-device'].prediction.historicalExposureIncomplete").value(true))
                .andExpect(jsonPath("$['risk-device'].prediction.currentGapReason").value("RUNNING_DATA_GAP"));
        long recovered = ingest(session.id(), base.plusSeconds(41), 5);
        awaitPrediction(recovered);
        mvc.perform(get("/api/readings/latest").param("deviceId", "risk-device").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$['risk-device'].prediction.source").value("MODEL"))
                .andExpect(jsonPath("$['risk-device'].prediction.qualityFlag").value("OK"))
                .andExpect(jsonPath("$['risk-device'].prediction.historicalExposureIncomplete").value(true))
                .andExpect(jsonPath("$['risk-device'].prediction.currentGapReason").value("NONE"))
                .andExpect(jsonPath("$['risk-device'].prediction.lastGapReason").value("RUNNING_DATA_GAP"));

        String wideSnapshot = snapshot(recovered);
        mvc.perform(put("/api/settings/thresholds/risk-device").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"temperatureLowerC":0,"temperatureUpperC":4,"humidityLowerPct":55,"humidityUpperPct":80}
                        """)).andExpect(status().isOk());
        long narrow = thresholds.findCurrentIdBySession(session.id()).orElseThrow();
        long underNewLimits = ingest(session.id(), base.plusSeconds(46), 5);
        awaitPrediction(underNewLimits);
        var underNew = mapper.readTree(snapshot(underNewLimits));
        assertThat(underNew.path("thresholdId").asLong()).isEqualTo(narrow).isNotEqualTo(version);
        assertThat(underNew.path("features").path("observedAboveUpperSeconds").asDouble()).isEqualTo(5);
        long next = ingest(session.id(), base.plusSeconds(51), 5);
        awaitPrediction(next);
        assertThat(mapper.readTree(snapshot(next)).path("features").path("observedAboveUpperSeconds").asDouble()).isEqualTo(10);
        assertThat(snapshot(recovered)).isEqualTo(wideSnapshot);
        assertThat(snapshot(first)).isEqualTo(original);
        var produce = devices.ensureDeviceWithActiveSession("produce", "test sensor", "VEG-COLD", "VEG-COLD");
        assertThat(thresholds.findCurrentBySession(produce.id()).orElseThrow().limits().temperatureUpperC())
                .isLessThan(4);
    }

    @Test
    void predictionWriteFailureLeavesCommittedReadingAndThresholdAlert() throws Exception {
        var session = devices.ensureDeviceWithActiveSession("risk-device", "test sensor", "RAW-MEAT", "RAW-MEAT");
        jdbc.sql("""
                CREATE TRIGGER fail_prediction BEFORE INSERT ON predictions
                BEGIN SELECT RAISE(ABORT, 'prediction unavailable'); END
                """).update();
        try {
            long id = ingest(session.id(), session.startedAt(), 9);
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(jdbc.sql("SELECT count(*) FROM readings WHERE id = ?").param(id).query(Long.class).single()).isOne();
            assertThat(jdbc.sql("SELECT count(*) FROM alerts WHERE kind = 'THRESHOLD' AND session_id = ?")
                    .param(session.id()).query(Long.class).single()).isOne();
            assertThat(jdbc.sql("SELECT count(*) FROM predictions WHERE reading_id = ?").param(id).query(Long.class).single())
                    .isZero();
            mvc.perform(get("/api/readings/latest").with(user("operator").roles("OPERATOR")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$['risk-device'].reading.id").value(id))
                    .andExpect(jsonPath("$['risk-device'].prediction").isEmpty());
        } finally {
            jdbc.sql("DROP TRIGGER fail_prediction").update();
        }
    }

    @Test
    void recoversPredictionOnlineAfterInsertBecomesAvailable() throws Exception {
        var session = devices.ensureDeviceWithActiveSession("risk-device", "test sensor", "RAW-MEAT", "RAW-MEAT");
        jdbc.sql("""
                CREATE TRIGGER fail_prediction BEFORE INSERT ON predictions
                BEGIN SELECT RAISE(ABORT, 'prediction unavailable'); END
                """).update();
        long id;
        try {
            id = ingest(session.id(), session.startedAt(), 9);
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(jdbc.sql("SELECT count(*) FROM readings WHERE id = ?").param(id).query(Long.class).single()).isOne();
            assertThat(jdbc.sql("SELECT count(*) FROM predictions WHERE reading_id = ?").param(id).query(Long.class).single())
                    .isZero();
        } finally {
            jdbc.sql("DROP TRIGGER fail_prediction").update();
        }
        assertThat(ingest(session.id(), session.startedAt(), 9)).isEqualTo(id);
        awaitPrediction(id);
        assertThat(jdbc.sql("SELECT count(*) FROM predictions WHERE reading_id=?").param(id).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM alerts WHERE kind='THRESHOLD'").query(Long.class).single()).isOne();
    }

    @Test
    void mediumAndHighPredictionsBecomeAggregatedRiskAlerts() throws Exception {
        var session = devices.ensureDeviceWithActiveSession("risk-device", "test sensor", "RAW-MEAT", "RAW-MEAT");
        long last = 0;
        // Readings may be at most 60 s ahead of server time, and the session starts now.
        for (int second = 0; second <= 55; second += 5) {
            last = ingest(session.id(), session.startedAt().plusSeconds(second), 9);
        }
        awaitPrediction(last);

        // Within one window every raised severity is one alert, last seen at its latest prediction.
        var expected = jdbc.sql("""
                SELECT p.risk_level || ' ' || p.source || ' ' || min(r.measured_at) || ' ' || max(r.measured_at)
                FROM predictions p JOIN readings r ON r.id = p.reading_id
                WHERE p.risk_level <> 'LOW'
                  AND p.quality_flag NOT IN ('INCOMPLETE_HISTORY', 'MONITORING_INTERRUPTED',
                                             'MONITORING_INTERRUPTION_UNKNOWN')
                GROUP BY p.risk_level ORDER BY min(r.measured_at)""").query(String.class).list();
        assertThat(expected).isNotEmpty();
        assertThat(jdbc.sql("""
                SELECT severity || ' ' || source || ' ' || opened_at || ' ' || last_seen_at FROM alerts
                WHERE kind = 'RISK' AND resolved_at IS NULL ORDER BY opened_at""").query(String.class).list())
                .isEqualTo(expected);
        assertThat(jdbc.sql("SELECT count(*) FROM alerts WHERE kind = 'THRESHOLD'").query(Long.class).single()).isOne();

        mvc.perform(get("/api/alerts").param("kind", "RISK").with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(expected.size()))
                .andExpect(jsonPath("$.items[0].kind").value("RISK"))
                .andExpect(jsonPath("$.items[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].risk.modelOrRuleVersion").isString())
                .andExpect(jsonPath("$.items[0].description").isString());
    }

    @Test
    void aFailedRiskAlertWriteLeavesThePredictionPendingUntilItSucceeds() throws Exception {
        var session = devices.ensureDeviceWithActiveSession("risk-device", "test sensor", "RAW-MEAT", "RAW-MEAT");
        jdbc.sql("""
                CREATE TRIGGER fail_risk_alert BEFORE INSERT ON alerts WHEN NEW.kind = 'RISK'
                BEGIN SELECT RAISE(ABORT, 'risk alert unavailable'); END
                """).update();
        long readings = 0;
        try {
            for (int second = 0; second <= 55; second += 5) {
                ingest(session.id(), session.startedAt().plusSeconds(second), 9);
                readings++;
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql(
                    "SELECT count(*) FROM prediction_jobs WHERE failed_attempts > 0").query(Long.class).single() > 0);
            // A prediction that would raise an alert rolled back with it, so its job stays and is retried.
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM prediction_jobs j JOIN predictions p ON p.reading_id = j.reading_id
                    """).query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM alerts WHERE kind = 'RISK'").query(Long.class).single()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER fail_risk_alert").update();
        }
        long total = readings;
        await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.sql("SELECT count(*) FROM predictions")
                .query(Long.class).single() == total);
        assertThat(jdbc.sql("SELECT count(*) FROM prediction_jobs").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM alerts WHERE kind = 'RISK'").query(Long.class).single()).isPositive();
        assertThat(jdbc.sql("SELECT count(*) - count(DISTINCT severity) FROM alerts WHERE kind = 'RISK'")
                .query(Long.class).single()).isZero();
    }

    private long ingest(long session, Instant at, double temperature) {
        return ingestion.ingest("risk-device", new ReadingSubmission("risk-device", session, at, temperature, 70))
                .reading().id();
    }

    private void awaitPrediction(long id) {
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.sql("SELECT EXISTS (SELECT 1 FROM predictions WHERE reading_id = ?)")
                .param(id).query(Boolean.class).single());
    }

    private String snapshot(long id) {
        return column(id, "feature_snapshot");
    }

    private String column(long id, String column) {
        return jdbc.sql("SELECT " + column + " FROM predictions WHERE reading_id = ?").param(id).query(String.class).single();
    }
}
