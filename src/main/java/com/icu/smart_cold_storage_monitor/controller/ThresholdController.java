package com.icu.smart_cold_storage_monitor.controller;

import com.icu.smart_cold_storage_monitor.controller.dto.DeviceThresholdResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.PageResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.ReviewNoteRequest;
import com.icu.smart_cold_storage_monitor.controller.dto.ThresholdLimitsRequest;
import com.icu.smart_cold_storage_monitor.controller.dto.ThresholdRequestResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.ThresholdReviewResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.ThresholdSubmissionResponse;
import com.icu.smart_cold_storage_monitor.model.ThresholdRequestStatus;
import com.icu.smart_cold_storage_monitor.security.AppUserPrincipal;
import com.icu.smart_cold_storage_monitor.service.PageQuery;
import com.icu.smart_cold_storage_monitor.service.ThresholdService;
import com.icu.smart_cold_storage_monitor.service.ThresholdService.SubmissionOutcome;
import com.icu.smart_cold_storage_monitor.service.ThresholdService.SubmissionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Threshold limits per device and operator change requests (FR-04). Every endpoint needs a signed-in user;
 * approval and rejection are restricted to ADMIN by {@code SecurityConfig}, and {@link ThresholdService} checks
 * roles again for every change.
 */
@Tag(name = "Thresholds", description = "Temperature and humidity limits per device, and operator change requests "
        + "that an administrator approves or rejects.")
@RestController
@RequestMapping("/api/settings")
public class ThresholdController {

    private final ThresholdService thresholds;

    public ThresholdController(ThresholdService thresholds) {
        this.thresholds = thresholds;
    }

    @Operation(summary = "Current limits of every device",
            description = "One row per registered device, in device id order, with its pending request if any.")
    @GetMapping("/thresholds")
    public List<DeviceThresholdResponse> list() {
        return thresholds.listDeviceThresholds().stream().map(DeviceThresholdResponse::from).toList();
    }

    @Operation(summary = "Current limits of one device")
    @GetMapping("/thresholds/{deviceId}")
    public DeviceThresholdResponse get(@PathVariable String deviceId) {
        return DeviceThresholdResponse.from(thresholds.getDeviceThreshold(deviceId));
    }

    @Operation(summary = "Set a device's limits",
            description = "An administrator's change applies at once (200 `APPLIED`). An operator's change becomes "
                    + "a request for administrator approval (202 `PENDING_APPROVAL`); a new request from the same "
                    + "operator replaces their pending one. Sending the current limits writes nothing "
                    + "(200 `UNCHANGED`). Changes apply to readings accepted afterwards.")
    @ApiResponse(responseCode = "200", description = "APPLIED or UNCHANGED",
            content = @Content(schema = @Schema(implementation = ThresholdSubmissionResponse.class)))
    @ApiResponse(responseCode = "202", description = "PENDING_APPROVAL",
            content = @Content(schema = @Schema(implementation = ThresholdSubmissionResponse.class)))
    @ApiResponse(responseCode = "409", description = "NO_ACTIVE_SESSION, or THRESHOLD_REQUEST_PENDING when another "
            + "user's request for this device is waiting for review")
    @PutMapping("/thresholds/{deviceId}")
    public ResponseEntity<ThresholdSubmissionResponse> submit(@PathVariable String deviceId,
                                                              @Valid @RequestBody ThresholdLimitsRequest request,
                                                              @AuthenticationPrincipal AppUserPrincipal user) {
        SubmissionResult result = thresholds.submit(user.getId(), deviceId, request.toLimits());
        HttpStatus status = result.outcome() == SubmissionOutcome.PENDING_APPROVAL ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(ThresholdSubmissionResponse.from(result));
    }

    @Operation(summary = "Threshold change requests",
            description = "Newest first. Administrators see every request, operators only their own. `status` "
                    + "can be repeated or comma-separated; `page` is zero-based, `size` defaults to 50 (max 200).")
    @GetMapping("/threshold-requests")
    public PageResponse<ThresholdRequestResponse> searchRequests(
            @RequestParam(required = false) String deviceId,
            @RequestParam(name = "status", required = false) List<ThresholdRequestStatus> statuses,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AppUserPrincipal user) {
        return PageResponse.from(
                thresholds.searchRequests(user.getId(), deviceId, statuses, PageQuery.of(page, size)),
                ThresholdRequestResponse::from);
    }

    @Operation(summary = "Approve a pending request (administrator)",
            description = "Applies the proposed limits as the device's new current version. 409 "
                    + "THRESHOLD_REQUEST_STALE when the device's limits changed after the request was made.")
    @PostMapping("/threshold-requests/{id}/approval")
    public ThresholdReviewResponse approve(@PathVariable long id,
                                           @Valid @RequestBody(required = false) ReviewNoteRequest request,
                                           @AuthenticationPrincipal AppUserPrincipal admin) {
        return ThresholdReviewResponse.from(thresholds.approve(admin.getId(), id, note(request)));
    }

    @Operation(summary = "Reject a pending request (administrator)")
    @PostMapping("/threshold-requests/{id}/rejection")
    public ThresholdReviewResponse reject(@PathVariable long id,
                                          @Valid @RequestBody(required = false) ReviewNoteRequest request,
                                          @AuthenticationPrincipal AppUserPrincipal admin) {
        return ThresholdReviewResponse.from(thresholds.reject(admin.getId(), id, note(request)));
    }

    @Operation(summary = "Cancel your own pending request")
    @PostMapping("/threshold-requests/{id}/cancellation")
    public ThresholdReviewResponse cancel(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal user) {
        return ThresholdReviewResponse.from(thresholds.cancel(user.getId(), id));
    }

    private static String note(ReviewNoteRequest request) {
        return request == null ? null : request.note();
    }
}
