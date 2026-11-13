package com.icu.smart_cold_storage_monitor.controller;

import com.icu.smart_cold_storage_monitor.controller.dto.AcknowledgmentRequest;
import com.icu.smart_cold_storage_monitor.controller.dto.AcknowledgmentResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.AlertResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.PageResponse;
import com.icu.smart_cold_storage_monitor.model.AlertKind;
import com.icu.smart_cold_storage_monitor.model.AlertState;
import com.icu.smart_cold_storage_monitor.security.AppUserPrincipal;
import com.icu.smart_cold_storage_monitor.service.AlertAcknowledgmentService;
import com.icu.smart_cold_storage_monitor.service.AlertQueryService;
import com.icu.smart_cold_storage_monitor.service.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alert history and acknowledgment (FR-04, FR-06, FR-08). Every endpoint needs a signed-in OPERATOR or ADMIN
 * ({@code /api/**} rule); {@link AlertAcknowledgmentService} checks the acting user again.
 */
@Tag(name = "Alerts", description = "Threshold (and later risk) alerts in one format, and their acknowledgment.")
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertQueryService queries;
    private final AlertAcknowledgmentService acknowledgments;

    public AlertController(AlertQueryService queries, AlertAcknowledgmentService acknowledgments) {
        this.queries = queries;
        this.acknowledgments = acknowledgments;
    }

    @Operation(summary = "Alert history",
            description = "Most recently opened first. `deviceId` and `kind` can be repeated or comma-separated; "
                    + "`from` (inclusive) and `to` (exclusive) bound the opening time; `page` is zero-based, "
                    + "`size` defaults to 50 (max 200).")
    @GetMapping
    public PageResponse<AlertResponse> search(
            @RequestParam(name = "deviceId", required = false) List<String> deviceIds,
            @RequestParam(name = "kind", required = false) List<AlertKind> kinds,
            @Parameter(description = "ACTIVE (not resolved) or RESOLVED")
            @RequestParam(required = false) AlertState state,
            @Parameter(description = "true for acknowledged alerts only, false for unacknowledged only")
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return PageResponse.from(
                queries.search(deviceIds, kinds, state, acknowledged, from, to, PageQuery.of(page, size)),
                AlertResponse::from);
    }

    @Operation(summary = "One alert")
    @ApiResponse(responseCode = "200", description = "The alert")
    @ApiResponse(responseCode = "404", description = "No alert with this id")
    @GetMapping("/{id}")
    public AlertResponse get(@PathVariable long id) {
        return AlertResponse.from(queries.get(id));
    }

    @Operation(summary = "Acknowledge an alert",
            description = "Records the signed-in user, the server time, and an optional note of up to 500 characters. "
                    + "Does not resolve the alert. Resolved alerts can be acknowledged too. The first "
                    + "acknowledgment is final: repeating it (by anyone, with any note) returns the existing one "
                    + "with outcome `ALREADY_ACKNOWLEDGED` and writes no audit event.")
    @ApiResponse(responseCode = "200", description = "ACKNOWLEDGED or ALREADY_ACKNOWLEDGED")
    @ApiResponse(responseCode = "404", description = "No alert with this id")
    @PostMapping("/{id}/acknowledgment")
    public AcknowledgmentResponse acknowledge(@PathVariable long id,
                                              @Valid @RequestBody(required = false) AcknowledgmentRequest request,
                                              @AuthenticationPrincipal AppUserPrincipal user) {
        return AcknowledgmentResponse.from(
                acknowledgments.acknowledge(user.getId(), id, request == null ? null : request.note()));
    }
}
