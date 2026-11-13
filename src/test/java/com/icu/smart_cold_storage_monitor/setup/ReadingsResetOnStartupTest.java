package com.icu.smart_cold_storage_monitor.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.config.IngestionProperties;
import com.icu.smart_cold_storage_monitor.service.ReadingRetentionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A database that already holds readings from an earlier run is started like a restart: the readings are gone,
 * while devices, sessions, and audit events stay. Does not extend {@code SqliteIntegrationTest}, whose per-test
 * cleanup would hide what startup did.
 */
@SpringBootTest(properties = "app.simulator.enabled=false")
class ReadingsResetOnStartupTest {

    @DynamicPropertySource
    static void databaseFromAnEarlierRun(DynamicPropertyRegistry registry) throws IOException {
        Path db = Files.createTempDirectory("scsm-reset-test-").resolve("test.db");
        SimpleDriverDataSource earlierRun = new SimpleDriverDataSource(new org.sqlite.JDBC(), "jdbc:sqlite:" + db);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(earlierRun);
        JdbcClient jdbc = JdbcClient.create(earlierRun);
        jdbc.sql("INSERT INTO devices (id, name) VALUES ('ESP32-SIM-001', 'Raw meat sensor')").update();
        jdbc.sql("""
                INSERT INTO storage_sessions (id, device_id, product_type, started_at)
                VALUES (1, 'ESP32-SIM-001', 'RAW-MEAT', '2026-11-12T08:00:00.000Z')""").update();
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                    INSERT INTO readings (device_id, session_id, measured_at, received_at, temperature_c, humidity_pct)
                    VALUES ('ESP32-SIM-001', 1, ?, ?, 4.0, 70.0)""")
                    .params("2026-11-12T08:00:0" + i + ".000Z", "2026-11-12T08:00:0" + i + ".000Z").update();
        }
        registry.add("app.database.path", db::toString);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ReadingRetentionService retention;

    @Test
    void startupClearsReadingsButKeepsDevicesAndSessions() {
        assertThat(count("readings")).isZero();
        assertThat(count("devices")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM storage_sessions WHERE ended_at IS NULL")
                .query(Long.class).single()).isEqualTo(1);

        // The flag decides: false keeps readings, true clears them.
        insertReading("2026-11-12T09:00:00.000Z");
        new ReadingsResetOnStartup(retention, new IngestionProperties("", false)).afterSingletonsInstantiated();
        assertThat(count("readings")).isEqualTo(1);

        new ReadingsResetOnStartup(retention, new IngestionProperties("", true)).afterSingletonsInstantiated();
        assertThat(count("readings")).isZero();
    }

    private void insertReading(String measuredAt) {
        jdbc.sql("""
                INSERT INTO readings (device_id, session_id, measured_at, received_at, temperature_c, humidity_pct)
                VALUES ('ESP32-SIM-001', 1, ?, ?, 4.0, 70.0)""").params(measuredAt, measuredAt).update();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
