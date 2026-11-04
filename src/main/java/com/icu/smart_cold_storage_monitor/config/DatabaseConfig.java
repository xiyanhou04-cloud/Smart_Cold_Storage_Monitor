package com.icu.smart_cold_storage_monitor.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite data source used by every repository.
 *
 * <p>Every pooled connection is opened with foreign-key enforcement, a bounded busy timeout, WAL journaling
 * (dashboard reads do not block the writer), and {@code BEGIN IMMEDIATE} transactions so that a transaction
 * takes the write lock up front and waits for the busy timeout instead of failing on a lock upgrade.
 * The schema itself is applied by Spring's SQL initializer from {@code schema.sql}, which only uses
 * {@code IF NOT EXISTS} statements, so a normal startup never resets data.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(DatabaseProperties properties) {
        Path dbFile = Path.of(properties.path()).toAbsolutePath().normalize();
        createParentDirectories(dbFile);

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setBusyTimeout(properties.busyTimeoutMs());
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqliteConfig);
        sqliteDataSource.setUrl("jdbc:sqlite:" + dbFile);

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("sqlite");
        hikari.setDataSource(sqliteDataSource);
        hikari.setMaximumPoolSize(properties.maxPoolSize());
        hikari.setMinimumIdle(1);
        // Fail fast if the pool is exhausted rather than queueing requests for the default 30 s.
        hikari.setConnectionTimeout(Math.max(properties.busyTimeoutMs() * 2L, 1_000L));
        return new HikariDataSource(hikari);
    }

    /**
     * JdbcTemplate with SQLite-aware exception translation. Spring Boot builds the auto-configured
     * {@code NamedParameterJdbcTemplate} and {@code JdbcClient} on top of this bean.
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setExceptionTranslator(new SqliteExceptionTranslator());
        return template;
    }

    /** UTC clock shared by services so tests can control "now". */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    private static void createParentDirectories(Path dbFile) {
        Path parent = dbFile.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create database directory " + parent, e);
        }
    }
}
