package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.service.ThresholdService.ReviewResult;

/** Result of approving, rejecting, or cancelling a threshold change request. */
public record ThresholdReviewResponse(ThresholdRequestResponse request, DeviceThresholdResponse threshold) {

    public static ThresholdReviewResponse from(ReviewResult result) {
        return new ThresholdReviewResponse(ThresholdRequestResponse.from(result.request()),
                DeviceThresholdResponse.from(result.threshold()));
    }
}
