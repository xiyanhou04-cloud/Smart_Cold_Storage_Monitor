package com.icu.smart_cold_storage_monitor.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.config.SqliteExceptionTranslator;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

/** Upgrading a database file created before a column existed, without a Spring context. */
class SchemaUpgradeOnStartupTest {

    @TempDir
    Path dir;

    SQLiteDataSource dataSource;
    JdbcClient jdbc;
    SchemaUpgradeOnStartup upgrader;

    @BeforeEach
    void database() {
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("old.db"));
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setExceptionTranslator(new SqliteExceptionTranslator());
        jdbc = JdbcClient.create(template);
        upgrader = new SchemaUpgradeOnStartup(template);
    }

    @Test
    void addsTheLocationColumnToAnOldDevicesTableAndKeepsItsRows() {
        // devices as created by the build before location_code existed.
        jdbc.sql("""
                CREATE TABLE devices (
                    id      TEXT    PRIMARY KEY CHECK (length(id) BETWEEN 1 AND 64),
                    name    TEXT    NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
                    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)))""").update();
        jdbc.sql("INSERT INTO devices (id, name) VALUES ('ESP32-SIM-001', 'Simulated RAW-MEAT sensor')").update();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        assertThat(upgrader.upgrade()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT id, name, location_code FROM devices").query().listOfRows())
                .containsExactly(row("ESP32-SIM-001", "Simulated RAW-MEAT sensor", null));
        assertThatThrownBy(() -> jdbc.sql("UPDATE devices SET location_code = ''").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrader.upgrade()).isZero();
    }

    @Test
    void addsTheHumidityColumnsToAnOldThresholdTableWithTheirChecks() {
        // threshold_settings as created by the build before humidity limits existed.
        jdbc.sql("CREATE TABLE devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, location_code TEXT)").update();
        jdbc.sql("""
                CREATE TABLE storage_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL,
                    product_type TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT, UNIQUE (id, device_id))""")
                .update();
        jdbc.sql("""
                CREATE TABLE threshold_settings (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL,
                    lower_c REAL NOT NULL, upper_c REAL NOT NULL, effective_from TEXT NOT NULL, changed_by INTEGER,
                    UNIQUE (id, session_id))""").update();
        jdbc.sql("INSERT INTO devices (id, name) VALUES ('D1', 'Device 1')").update();
        jdbc.sql("INSERT INTO storage_sessions (device_id, product_type, started_at) VALUES ('D1', 'X', "
                + "'2026-11-12T08:00:00.000Z')").update();
        jdbc.sql("INSERT INTO threshold_settings (session_id, lower_c, upper_c, effective_from) VALUES (1, 0, 4, "
                + "'2026-11-12T08:00:00.000Z')").update();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        assertThat(upgrader.upgrade()).isEqualTo(2);

        assertThat(jdbc.sql("SELECT lower_humidity_pct IS NULL AND upper_humidity_pct IS NULL FROM threshold_settings")
                .query(Boolean.class).single()).isTrue();
        String insert = "INSERT INTO threshold_settings (session_id, lower_c, upper_c, effective_from, "
                + "lower_humidity_pct, upper_humidity_pct) VALUES (1, 0, 4, '2026-11-12T08:00:01.000Z', ?, ?)";
        jdbc.sql(insert).params(55.0, 80.0).update();
        assertThatThrownBy(() -> jdbc.sql(insert).params(55.0, null).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(insert).params(80.0, 55.0).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(upgrader.upgrade()).isZero();
    }

    @Test
    void aDatabaseCreatedFromTheCurrentSchemaNeedsNoUpgrade() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        assertThat(upgrader.upgrade()).isZero();
    }

    @Test
    void upgradedColumnsMatchTheCurrentSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        for (SchemaUpgradeOnStartup.AddColumn upgrade : SchemaUpgradeOnStartup.UPGRADES) {
            List<Map<String, Object>> column = jdbc.sql("""
                    SELECT type, "notnull", dflt_value FROM pragma_table_info(:table) WHERE name = :column""")
                    .param("table", upgrade.table()).param("column", upgrade.column()).query().listOfRows();
            assertThat(column).as(upgrade.table() + "." + upgrade.column()).hasSize(1);
            assertThat(upgrade.definition()).startsWith(column.getFirst().get("type") + " ");
            assertThat(column.getFirst().get("notnull")).isEqualTo(0);
        }
    }

    private static Map<String, Object> row(String id, String name, String location) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("location_code", location);
        return row;
    }
}
