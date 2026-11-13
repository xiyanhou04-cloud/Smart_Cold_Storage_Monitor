package com.icu.smart_cold_storage_monitor.model;

import java.time.Instant;

/**
 * One accepted sensor reading (row of {@code readings}). {@code thresholdId} is null until threshold versioning
 * (FR-04) exists.
 */
public record Reading(long id, String deviceId, long sessionId, Instant measuredAt, Instant receivedAt,
                      double temperatureC, double humidityPct, Long thresholdId) {
}
