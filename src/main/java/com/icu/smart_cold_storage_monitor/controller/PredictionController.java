package com.icu.smart_cold_storage_monitor.controller;

import com.icu.smart_cold_storage_monitor.controller.dto.PageResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.PredictionResponse;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.service.PageQuery;
import com.icu.smart_cold_storage_monitor.service.PredictionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Risk prediction history (FR-05). Any signed-in OPERATOR or ADMIN ({@code /api/**} rule). */
@Tag(name = "Predictions", description = "Stored risk predictions, one per scored reading.")
@RestController
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PredictionQueryService queries;

    public PredictionController(PredictionQueryService queries) {
        this.queries = queries;
    }

    @Operation(summary = "Prediction history",
            description = "Most recently measured reading first. `deviceId` and `riskLevel` can be repeated or "
                    + "comma-separated; `from` (inclusive) and `to` (exclusive) bound the scored reading's measured "
                    + "time; `page` is zero-based, `size` defaults to 50 (max 200). An unknown device matches "
                    + "nothing.")
    @ApiResponse(responseCode = "200", description = "One page of predictions")
    @ApiResponse(responseCode = "400", description = "Unknown risk level, 'to' not after 'from', or invalid paging")
    @GetMapping
    public PageResponse<PredictionResponse> search(
            @Parameter(description = "Device ids; repeat or comma-separate")
            @RequestParam(name = "deviceId", required = false) List<String> deviceIds,
            @Parameter(description = "LOW, MEDIUM, HIGH; repeat or comma-separate")
            @RequestParam(name = "riskLevel", required = false) List<RiskLevel> riskLevels,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return PageResponse.from(queries.search(deviceIds, riskLevels, from, to, PageQuery.of(page, size)),
                PredictionResponse::from);
    }
}
