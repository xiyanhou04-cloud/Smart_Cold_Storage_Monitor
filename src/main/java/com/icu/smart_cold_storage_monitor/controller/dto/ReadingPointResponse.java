package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.service.ReadingQueryService.ReadingPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** One chart point: UTC measurement time and the value (°C or %RH, depending on the list it is in). */
public record ReadingPointResponse(
        @Schema(example = "2026-11-13T04:43:41.203Z") Instant timestamp,
        @Schema(example = "4.0032") double value) {

    public static ReadingPointResponse from(ReadingPoint point) {
        return new ReadingPointResponse(point.timestamp(), point.value());
    }
}
