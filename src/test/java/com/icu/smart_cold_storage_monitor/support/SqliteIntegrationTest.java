package com.icu.smart_cold_storage_monitor.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests: each Spring test context gets its own temporary SQLite file (never another
 * database engine, never the developer's ./data database). Tables are emptied before every test; add new tables
 * to {@link #cleanDatabase()} in foreign-key order.
 */
@SpringBootTest
public abstract class SqliteIntegrationTest {

    /** Device secret configured for every integration test context. */
    public static final String TEST_DEVICE_SECRET = "test-device-secret-0123456789";

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("scsm-test-");
        Path db = dir.resolve("test.db");
        registry.add("app.database.path", db::toString);
        // The built-in simulator would post readings in the background; tests that need it start their own context.
        registry.add("app.simulator.enabled", () -> "false");
        registry.add("app.ingestion.device-secret", () -> TEST_DEVICE_SECRET);
    }

    @BeforeEach
    void cleanDatabase() {
        // Children before parents because foreign keys are enforced.
        jdbc.sql("DELETE FROM alerts").update();
        jdbc.sql("DELETE FROM readings").update();
        jdbc.sql("DELETE FROM threshold_change_requests").update();
        jdbc.sql("DELETE FROM threshold_settings").update();
        jdbc.sql("DELETE FROM storage_sessions").update();
        jdbc.sql("DELETE FROM devices").update();
        jdbc.sql("DELETE FROM audit_events").update();
        jdbc.sql("DELETE FROM users").update();
    }
}
