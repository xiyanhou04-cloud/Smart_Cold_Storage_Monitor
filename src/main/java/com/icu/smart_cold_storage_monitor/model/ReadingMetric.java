package com.icu.smart_cold_storage_monitor.model;

import java.util.Locale;
import java.util.Optional;

/** A measured quantity of a reading that can be queried on its own. */
public enum ReadingMetric {
    TEMPERATURE,
    HUMIDITY;

    /** Parses {@code temperature} or {@code humidity}, ignoring case and surrounding spaces. */
    public static Optional<ReadingMetric> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "temperature" -> Optional.of(TEMPERATURE);
            case "humidity" -> Optional.of(HUMIDITY);
            default -> Optional.empty();
        };
    }
}
