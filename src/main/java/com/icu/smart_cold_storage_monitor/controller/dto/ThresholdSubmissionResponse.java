package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.service.ThresholdService.SubmissionOutcome;
import com.icu.smart_cold_storage_monitor.service.ThresholdService.SubmissionResult;
import io.swagger.v3.oas.annotations.media.Schema;

/** Result of {@code PUT /api/settings/thresholds/{deviceId}}. */
public record ThresholdSubmissionResponse(
        @Schema(description = "APPLIED (administrator, now current), UNCHANGED (same as current, nothing written), "
                + "or PENDING_APPROVAL (operator, waiting for an administrator)")
        SubmissionOutcome outcome,
        @Schema(description = "The device's limits after the submission")
        DeviceThresholdResponse threshold,
        @Schema(description = "The pending request for PENDING_APPROVAL, otherwise null")
        ThresholdRequestResponse request) {

    public static ThresholdSubmissionResponse from(SubmissionResult result) {
        return new ThresholdSubmissionResponse(result.outcome(), DeviceThresholdResponse.from(result.threshold()),
                result.request() == null ? null : ThresholdRequestResponse.from(result.request()));
    }
}
