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
