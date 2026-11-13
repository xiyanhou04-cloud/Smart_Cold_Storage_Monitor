package com.icu.smart_cold_storage_monitor.controller;

import com.icu.smart_cold_storage_monitor.controller.dto.DeviceReadingsResponse;
import com.icu.smart_cold_storage_monitor.service.ReadingQueryService;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import com.icu.smart_cold_storage_monitor.web.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard query of temperature and humidity readings grouped by device (FR-03). Any signed-in user (OPERATOR or
 * ADMIN) may call it; {@code SecurityConfig} requires sign-in for {@code /api/**}.
 */
@Tag(name = ReadingController.TAG, description = ReadingController.TAG_DESCRIPTION)
@RestController
public class ReadingQueryController {

    public static final String QUERY_URL = "/api/readings/query";

    private final ReadingQueryService readings;

    public ReadingQueryController(ReadingQueryService readings) {
        this.readings = readings;
    }

    @GetMapping(QUERY_URL)
    @Operation(summary = "Query readings by device",
            description = "Returns an object whose keys are device ids. Each device has `temperatureC` and/or "
                    + "`humidityPct`: lists of `{timestamp, value}` points, oldest first. Defaults: every device, both "
                    + "metrics, the last 5 minutes. The range is [from, to) and spans at most 6 hours. Requested "
                    + "devices without readings in the range have empty lists.")
    @ApiResponse(responseCode = "200", description = "Readings grouped by device id")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: unknown device, unknown metric, or an "
            + "invalid time range", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Not signed in",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public Map<String, DeviceReadingsResponse> query(
            @Parameter(description = "Device id; repeat or comma-separate for several. Default: every device.",
                    example = "ESP32-SIM-001")
            @RequestParam(name = "deviceId", required = false) List<String> deviceIds,
            @Parameter(description = "Inclusive UTC start, e.g. 2026-11-13T04:40:00Z. Cannot be combined with "
                    + "window.")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive UTC end. Default: now.")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Length of the range ending at 'to' when 'from' is absent, e.g. PT10M or 10m. "
                    + "Default: 5 minutes; at most 6 hours.", example = "10m")
            @RequestParam(required = false) String window,
            @Parameter(description = "temperature and/or humidity; repeat or comma-separate. Default: both.",
                    example = "temperature")
            @RequestParam(name = "metric", required = false) List<String> metrics) {
        Map<String, DeviceReadingsResponse> body = new LinkedHashMap<>();
        readings.query(deviceIds, from, to, parseWindow(window), metrics)
                .forEach((device, deviceReadings) -> body.put(device, DeviceReadingsResponse.from(deviceReadings)));
        return body;
    }

    private static Duration parseWindow(String window) {
        if (window == null || window.isBlank()) {
            return null;
        }
        try {
            return DurationStyle.detectAndParse(window.strip());
        } catch (IllegalArgumentException ex) {
            throw new InvalidInputException("window", "'window' must be a duration such as PT10M or 10m.");
        }
    }
}
