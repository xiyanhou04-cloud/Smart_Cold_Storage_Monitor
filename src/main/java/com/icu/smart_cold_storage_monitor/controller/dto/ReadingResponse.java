package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.Reading;
import java.time.Instant;

/** A stored reading; {@code thresholdId} is null until threshold versioning exists. */
public record ReadingResponse(long id, String deviceId, long sessionId, Instant measuredAt, Instant receivedAt,
                              double temperatureC, double humidityPct, Long thresholdId) {

    public static ReadingResponse from(Reading reading) {
        return new ReadingResponse(reading.id(), reading.deviceId(), reading.sessionId(), reading.measuredAt(),
                reading.receivedAt(), reading.temperatureC(), reading.humidityPct(), reading.thresholdId());
    }
}
