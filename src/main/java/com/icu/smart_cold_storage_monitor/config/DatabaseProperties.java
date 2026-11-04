package com.icu.smart_cold_storage_monitor.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * SQLite connection settings bound from {@code app.database.*}.
 *
 * @param path          location of the SQLite database file; parent directories are created on startup
 * @param busyTimeoutMs how long a connection waits for SQLite's single write lock before failing
 * @param maxPoolSize   upper bound on pooled connections (SQLite allows one writer at a time)
 */
@Validated
@ConfigurationProperties(prefix = "app.database")
public record DatabaseProperties(
        @NotBlank @DefaultValue("./data/smart-cold-storage.db") String path,
        @Min(100) @Max(60_000) @DefaultValue("5000") int busyTimeoutMs,
        @Min(1) @Max(16) @DefaultValue("4") int maxPoolSize) {
}
