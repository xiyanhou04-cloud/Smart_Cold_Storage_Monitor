package com.icu.smart_cold_storage_monitor.setup;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adds columns that {@code schema.sql} gained after a table was first released to a database file created by an
 * older build. {@code CREATE TABLE IF NOT EXISTS} leaves an existing table unchanged, and SQLite has no
 * {@code ADD COLUMN IF NOT EXISTS}, so each column is added only when {@code pragma_table_info} does not list it.
 *
 * <p>Runs once, right after {@code schema.sql} has been applied and before any other startup work uses the tables.
 * Existing rows are kept; an added column is NULL in them. Only nullable columns without a non-constant default can
 * be added this way (an SQLite restriction), so each entry must match its definition in {@code schema.sql}.
 */
@Component
@DependsOnDatabaseInitialization
public class SchemaUpgradeOnStartup implements InitializingBean {

    /** A column to add to {@code table} with {@code ALTER TABLE ... ADD COLUMN column definition}. */
    record AddColumn(String table, String column, String definition) {
    }

    /** Fixed SQL only: table and column names cannot be bound as parameters. Keep in sync with schema.sql. */
    static final List<AddColumn> UPGRADES = List.of(
            new AddColumn("devices", "location_code",
                    "TEXT CHECK (location_code IS NULL OR length(location_code) BETWEEN 1 AND 64)"),
            new AddColumn("threshold_settings", "lower_humidity_pct",
                    "REAL CHECK (lower_humidity_pct IS NULL OR lower_humidity_pct BETWEEN 0 AND 100)"),
            // Added after lower_humidity_pct, so its check may refer to that column.
            new AddColumn("threshold_settings", "upper_humidity_pct",
                    "REAL CHECK ((upper_humidity_pct IS NULL) = (lower_humidity_pct IS NULL) AND "
                            + "(upper_humidity_pct IS NULL OR (upper_humidity_pct BETWEEN 0 AND 100 "
                            + "AND lower_humidity_pct < upper_humidity_pct)))"));

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgradeOnStartup.class);

    private final JdbcTemplate jdbc;

    public SchemaUpgradeOnStartup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        upgrade();
    }

    /** Adds every missing column of {@link #UPGRADES}; returns how many were added (0 when already current). */
    int upgrade() {
        int added = 0;
        for (AddColumn upgrade : UPGRADES) {
            List<String> columns = jdbc.queryForList("SELECT name FROM pragma_table_info(?)", String.class,
                    upgrade.table());
            if (columns.isEmpty() || columns.contains(upgrade.column())) {
                continue; // table not created (schema.sql creates it) or column already present
            }
            jdbc.execute("ALTER TABLE " + upgrade.table() + " ADD COLUMN " + upgrade.column() + " "
                    + upgrade.definition());
            log.info("Upgraded database schema: added column {}.{}", upgrade.table(), upgrade.column());
            added++;
        }
        return added;
    }
}
