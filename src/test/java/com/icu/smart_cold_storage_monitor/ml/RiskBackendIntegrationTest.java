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
                .andExpect(jsonPath("$['risk-device'].prediction.qualityFlag").value("INCOMPLETE_HISTORY"));
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
