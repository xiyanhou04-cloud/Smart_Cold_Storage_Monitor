package com.icu.smart_cold_storage_monitor.setup;

import com.icu.smart_cold_storage_monitor.config.IngestionProperties;
import com.icu.smart_cold_storage_monitor.service.ReadingRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Optional demo reset of readings and their predictions. Devices, sessions and audit events are kept.
 *
 * <p>Runs once all beans exist, which is after {@code schema.sql} has been applied and before the HTTP server
 * starts, so no reading accepted by this run can be deleted. The default keeps history for startup recovery.
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
