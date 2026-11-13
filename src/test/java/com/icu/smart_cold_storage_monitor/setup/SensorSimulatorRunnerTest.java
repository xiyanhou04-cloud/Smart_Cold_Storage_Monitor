package com.icu.smart_cold_storage_monitor.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the real server on a random port with the built-in simulator enabled and checks that readings arrive in
 * the database through {@code POST /api/readings} without anyone signing in. Does not extend
 * {@code SqliteIntegrationTest}: emptying the tables under a running simulator would break it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "app.simulator.enabled=true",
        "app.simulator.interval=1s",
        "app.simulator.devices=ESP32-SIM-001,ESP32-SIM-005"})
class SensorSimulatorRunnerTest {

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("scsm-sim-test-");
        registry.add("app.database.path", () -> dir.resolve("test.db").toString());
        // No device secret configured: the runner must work with the generated one.
        registry.add("app.ingestion.device-secret", () -> "");
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SensorSimulatorRunner runner;

    @Autowired
    AuditEventRepository auditEvents;

    @Test
    void readingsArriveWithoutSignInAndCarrySendTimeStamps() throws InterruptedException {
        Instant started = Instant.now().minusSeconds(5);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (readingCount() < 6 && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }

        assertThat(runner.isRunning()).isTrue();
        assertThat(readingCount()).isGreaterThanOrEqualTo(6);
        assertThat(jdbc.sql("SELECT id FROM devices ORDER BY id").query(String.class).list())
                .containsExactly("ESP32-SIM-001", "ESP32-SIM-005");
        List<Map<String, Object>> sessions = jdbc.sql(
                "SELECT device_id, product_type FROM storage_sessions WHERE ended_at IS NULL ORDER BY device_id")
                .query().listOfRows();
        assertThat(sessions).extracting(r -> r.get("product_type")).containsExactly("RAW-MEAT", "FROZEN");

        // The first recorded samples of each device, in order, with measured_at near now rather than 2026-11-05.
        List<Double> rawMeat = jdbc.sql("""
                        SELECT temperature_c FROM readings WHERE device_id = 'ESP32-SIM-001' ORDER BY measured_at
                        LIMIT 3""").query(Double.class).list();
        assertThat(rawMeat).startsWith(4.0032, 4.0486);
        String firstMeasured = jdbc.sql("SELECT min(measured_at) FROM readings").query(String.class).single();
        assertThat(Instant.parse(firstMeasured)).isAfter(started);
        assertThat(auditEvents.findRecent(10)).filteredOn(e -> e.eventType() == AuditEventType.STORAGE_SESSION_STARTED)
                .hasSize(2);
    }

    private long readingCount() {
        return jdbc.sql("SELECT count(*) FROM readings").query(Long.class).single();
    }
}
