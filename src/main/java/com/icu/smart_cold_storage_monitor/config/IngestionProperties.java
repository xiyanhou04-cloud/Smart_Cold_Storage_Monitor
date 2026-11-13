package com.icu.smart_cold_storage_monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Device ingestion settings bound from {@code app.ingestion.*}.
 *
 * @param deviceSecret           shared secret for device tokens ({@code APP_DEVICE_SECRET}, at least 16
 *                               characters). When blank, a random secret is generated for each run, which only the
 *                               built-in simulator knows.
 * @param clearReadingsOnStartup empty the {@code readings} table on every start
 *                               ({@code APP_READINGS_CLEAR_ON_STARTUP}, default true)
 */
@ConfigurationProperties(prefix = "app.ingestion")
public record IngestionProperties(@DefaultValue("") String deviceSecret,
                                  @DefaultValue("true") boolean clearReadingsOnStartup) {

    @Override
    public String toString() {
        return "IngestionProperties[deviceSecret=" + (deviceSecret.isBlank() ? "<generated>" : "<configured>")
                + ", clearReadingsOnStartup=" + clearReadingsOnStartup + "]";
    }
}
