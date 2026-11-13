package com.icu.smart_cold_storage_monitor.setup;

import com.icu.smart_cold_storage_monitor.config.IngestionProperties;
import com.icu.smart_cold_storage_monitor.service.ReadingRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Empties the {@code readings} table on every backend start so the database does not keep growing across runs
 * (the built-in simulator adds about 120,000 rows per day). Devices, storage sessions, and audit events are kept.
 *
 * <p>Runs once all beans exist, which is after {@code schema.sql} has been applied and before the HTTP server
 * starts, so no reading accepted by this run can be deleted. Set {@code APP_READINGS_CLEAR_ON_STARTUP=false} to keep
 * readings across restarts (the design's reliability requirement, and needed to collect history for training).
 */
@Component
public class ReadingsResetOnStartup implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ReadingsResetOnStartup.class);

    private final ReadingRetentionService retention;
    private final IngestionProperties properties;

    public ReadingsResetOnStartup(ReadingRetentionService retention, IngestionProperties properties) {
        this.retention = retention;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.clearReadingsOnStartup()) {
            log.info("Keeping stored readings across restarts (APP_READINGS_CLEAR_ON_STARTUP=false).");
            return;
        }
        int removed = retention.clearAllReadings();
        log.info("Cleared {} stored reading(s) on startup. Set APP_READINGS_CLEAR_ON_STARTUP=false to keep them.",
                removed);
    }
}
