package com.icu.smart_cold_storage_monitor.simulator;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Request body for {@code POST /api/readings}, following the design's reading contract
 * (deviceId, sessionId, timestamp, temperatureC, humidityPct). Provisional until the receiving
 * endpoint exists.
 *
 * @param sessionId storage session id; {@code null} only in dry-run mode
 * @param timestamp UTC send time, fixed width with milliseconds
 */
record ReadingPayload(String deviceId, Long sessionId, String timestamp,
                      double temperatureC, double humidityPct) {

    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Truncates to milliseconds so a retried payload carries exactly the same timestamp text. */
    static String formatTimestamp(Instant instant) {
        return TIMESTAMP.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }
}
