package com.icu.smart_cold_storage_monitor.config;

import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Default threshold limits bound from {@code app.thresholds.*}. Every new storage session starts with the default
 * for its device's {@code location_code}; a device without a location, or with a location not listed here, gets
 * {@code fallback}. Invalid limits stop the application at startup.
 *
 * @param fallback  limits for a device whose location has no entry in {@code locations}
 * @param locations limits by exact location code, e.g. {@code RAW-MEAT}. In YAML, write each key in brackets
 *                  ({@code "[RAW-MEAT]"}) so the binder keeps it unchanged.
 */
@ConfigurationProperties(prefix = "app.thresholds")
public record ThresholdProperties(ThresholdLimits fallback, Map<String, ThresholdLimits> locations) {

    /** Used when {@code app.thresholds.fallback} is not configured: a general chilled-storage range. */
    public static final ThresholdLimits BUILT_IN_FALLBACK = new ThresholdLimits(0, 8, 30, 95);

    public ThresholdProperties {
        fallback = fallback == null ? BUILT_IN_FALLBACK : fallback;
        locations = locations == null ? Map.of() : Map.copyOf(locations);
        check("app.thresholds.fallback", fallback);
        locations.forEach((code, limits) -> check("app.thresholds.locations[" + code + "]", limits));
    }

    /** The default limits for a device in {@code locationCode}, which may be {@code null}. */
    public ThresholdLimits defaultFor(String locationCode) {
        ThresholdLimits limits = locationCode == null ? null : locations.get(locationCode);
        return limits == null ? fallback : limits;
    }

    private static void check(String property, ThresholdLimits limits) {
        if (limits == null) {
            throw new IllegalArgumentException(property + " must set all four limits.");
        }
        limits.findViolation().ifPresent(violation -> {
            throw new IllegalArgumentException(property + "." + violation.field() + ": " + violation.message());
        });
    }
}
