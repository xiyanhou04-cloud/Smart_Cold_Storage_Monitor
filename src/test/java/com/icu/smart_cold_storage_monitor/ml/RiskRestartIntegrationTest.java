package com.icu.smart_cold_storage_monitor.ml;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.SmartColdStorageMonitorApplication;
import com.icu.smart_cold_storage_monitor.repository.ReadingRepository;
import com.icu.smart_cold_storage_monitor.repository.ThresholdSettingRepository;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

class RiskRestartIntegrationTest {
    @TempDir Path directory;

    @Test
    void actualRestartRetainsReadingsAndRecoversPendingInMeasurementOrder() throws Exception {
        long first;
        long second;
        try (var app = start("--app.risk.model-base=classpath:/missing-risk-model")) {
            waitForWorker(app);
            var session = app.getBean(DeviceSessionService.class)
                    .ensureDeviceWithActiveSession("restart-device", "test sensor", "RAW-MEAT", "RAW-MEAT");
            long threshold = app.getBean(ThresholdSettingRepository.class).findCurrentIdBySession(session.id()).orElseThrow();
            var readings = app.getBean(ReadingRepository.class);
            var base = session.startedAt();
            // Direct inserts represent readings committed before their prediction task could run.
            second = readings.insert(session.deviceId(), session.id(), base.plusSeconds(5), base, 5, 70, threshold).id();
            first = readings.insert(session.deviceId(), session.id(), base, base, 5, 70, threshold).id();
        }
        try (var app = start("--app.risk.model-version=incompatible-version")) {
            waitForWorker(app);
            var jdbc = app.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT reading_id FROM predictions ORDER BY id").query(Long.class).list())
                    .containsExactly(first, second);
            var snapshot = jdbc.sql("SELECT feature_snapshot FROM predictions WHERE reading_id = ?")
                    .param(second).query(String.class).single();
            assertThat(app.getBean(ObjectMapper.class).readTree(snapshot).path("features")
                    .path("observedAboveUpperSeconds").asDouble()).isEqualTo(5);
            assertThat(jdbc.sql("SELECT source || ':' || quality_flag FROM predictions").query(String.class).list())
                    .containsOnly("RULES:MODEL_INCOMPATIBLE");
        }
        try (var app = start("--app.risk.model-base=classpath:/missing-risk-model")) {
            waitForWorker(app);
            assertThat(app.getBean(JdbcClient.class).sql("SELECT source || ':' || quality_flag FROM predictions")
                    .query(String.class).list()).hasSize(2).containsOnly("RULES:MODEL_INCOMPATIBLE");
        }
    }

    private ConfigurableApplicationContext start(String modelArgument) {
        return new SpringApplicationBuilder(SmartColdStorageMonitorApplication.class).run(
                "--server.port=0", "--app.simulator.enabled=false",
                "--app.database.path=" + directory.resolve("restart.sqlite"), modelArgument);
    }

    private static void waitForWorker(ConfigurableApplicationContext app) throws Exception {
        app.getBean("riskExecutor", ThreadPoolTaskExecutor.class).submit(() -> {}).get(10, TimeUnit.SECONDS);
    }
}
