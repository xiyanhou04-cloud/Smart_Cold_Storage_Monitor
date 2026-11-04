package com.icu.smart_cold_storage_monitor.repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Converts between {@link Instant} and the TEXT representation stored in SQLite.
 *
 * <p>Always writes fixed-width UTC ISO 8601 with millisecond precision ({@code 2026-11-04T18:30:00.000Z}) so that
 * string comparison and {@code ORDER BY} on timestamp columns match chronological order.
 * ({@link Instant#toString()} drops zero fractions and would sort {@code ...:00Z} after {@code ...:00.500Z}.)
 */
public final class SqlTimestamps {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private SqlTimestamps() {
    }

    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }

    public static Instant parse(String text) {
        return Instant.parse(text);
    }
}
