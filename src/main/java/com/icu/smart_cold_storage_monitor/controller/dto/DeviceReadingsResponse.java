package com.icu.smart_cold_storage_monitor.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.icu.smart_cold_storage_monitor.service.ReadingQueryService.DeviceReadings;
import com.icu.smart_cold_storage_monitor.service.ReadingQueryService.ReadingPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One device's readings, oldest first, with temperature and humidity as separate lists so a chart can plot each
 * directly. A metric that was not requested is omitted; a requested metric without data is an empty list.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceReadingsResponse(
        @Schema(description = "Temperature in °C; omitted unless requested") List<ReadingPointResponse> temperatureC,
        @Schema(description = "Relative humidity in %RH; omitted unless requested")
        List<ReadingPointResponse> humidityPct) {

    public static DeviceReadingsResponse from(DeviceReadings readings) {
        return new DeviceReadingsResponse(map(readings.temperatureC()), map(readings.humidityPct()));
    }

    private static List<ReadingPointResponse> map(List<ReadingPoint> points) {
        return points == null ? null : points.stream().map(ReadingPointResponse::from).toList();
    }
}
