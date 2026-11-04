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
 * database engine, never the developer's ./data database). Tables are emptied before every test.
 */
@SpringBootTest
public abstract class SqliteIntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("scsm-test-");
        Path db = dir.resolve("test.db");
        registry.add("app.database.path", db::toString);
    }

    @BeforeEach
    void cleanDatabase() {
        // Children before parents because foreign keys are enforced.
        jdbc.sql("DELETE FROM audit_events").update();
        jdbc.sql("DELETE FROM users").update();
    }
}
