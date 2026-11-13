package com.icu.smart_cold_storage_monitor.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.SmartColdStorageMonitorApplication;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With the built-in loop disabled, startup still provisions devices and sessions, and the command-line simulator
 * (sharing APP_DEVICE_SECRET) stores readings through the real server's {@code POST /api/readings}.
 */
@SpringBootTest(classes = SmartColdStorageMonitorApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "app.simulator.enabled=false")
class ExternalSimulatorEndToEndTest {
    static final String SECRET = "external-simulator-secret-0123";

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("scsm-ext-sim-test-");
        registry.add("app.database.path", () -> dir.resolve("test.db").toString());
        registry.add("app.ingestion.device-secret", () -> SECRET);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @TempDir
    Path dir;

    @Test
    void commandLineSimulatorStoresReadingsInTheDatabase() throws IOException {
        assertThat(jdbc.sql("SELECT count(*) FROM storage_sessions WHERE ended_at IS NULL").query(Long.class).single())
                .isEqualTo(7);
        assertThat(jdbc.sql("SELECT count(*) FROM readings").query(Long.class).single()).isZero();
        long session = jdbc.sql("SELECT id FROM storage_sessions WHERE device_id = 'ESP32-SIM-001'")
                .query(Long.class).single();
        Path data = dir.resolve("two.jsonl");
        Files.write(data, Files.readAllLines(Path.of("data/monitoring_timestamped.jsonl")).stream()
                .filter(l -> l.contains("ESP32-SIM-001"))
                .limit(2)
                .toList());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = SimulatorMain.run(new String[] {"--mode=http", "--data=" + data,
                "--url=http://127.0.0.1:" + port + "/api/readings", "--sessions=ESP32-SIM-001=" + session,
                "--interval=1", "--cycles=1"}, Map.of("APP_DEVICE_SECRET", SECRET),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(code).as(out.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("-> 201", "accepted=2");
        List<Double> stored = jdbc.sql("SELECT temperature_c FROM readings ORDER BY measured_at")
                .query(Double.class).list();
        assertThat(stored).containsExactly(4.0032, 4.0486);
    }
}
