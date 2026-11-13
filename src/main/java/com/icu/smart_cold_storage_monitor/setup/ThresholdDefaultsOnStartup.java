package com.icu.smart_cold_storage_monitor.setup;

import com.icu.smart_cold_storage_monitor.service.ThresholdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Gives every active storage session without threshold limits the default of its device's location, e.g. sessions
 * in a database created before thresholds existed. New sessions get theirs when they start, so after the first run
 * this finds nothing to do.
 *
 * <p>Runs once all beans exist, which is after {@code schema.sql} and the schema upgrade, and before the HTTP server
 * starts accepting readings.
 */
@Component
public class ThresholdDefaultsOnStartup implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ThresholdDefaultsOnStartup.class);

    private final ThresholdService thresholds;

    public ThresholdDefaultsOnStartup(ThresholdService thresholds) {
        this.thresholds = thresholds;
    }

    @Override
    public void afterSingletonsInstantiated() {
        int assigned = thresholds.assignMissingDefaults();
        if (assigned > 0) {
            log.info("Assigned default threshold limits to {} active storage session(s) that had none.", assigned);
        }
    }
}
