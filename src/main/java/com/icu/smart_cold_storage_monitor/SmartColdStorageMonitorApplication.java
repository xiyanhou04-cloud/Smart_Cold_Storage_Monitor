package com.icu.smart_cold_storage_monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Backend entry point.
 *
 * <p>Startup is safe to repeat. On the first run it creates the SQLite database file ({@code app.database.path}),
 * applies {@code schema.sql}, and creates an administrator (see {@code AdminBootstrapRunner}). On later runs the
 * existing database and administrator are kept unchanged.
 */
@SpringBootApplication
public class SmartColdStorageMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartColdStorageMonitorApplication.class, args);
    }

}
