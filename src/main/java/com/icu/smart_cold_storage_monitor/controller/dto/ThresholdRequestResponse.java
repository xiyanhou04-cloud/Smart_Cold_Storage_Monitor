package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.ThresholdChangeRequest;
import com.icu.smart_cold_storage_monitor.model.ThresholdRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A threshold change request with the proposed limits and its review state. */
public record ThresholdRequestResponse(
        long id,
        String deviceId,
        ThresholdRequestStatus status,
        double temperatureLowerC,
        double temperatureUpperC,
        double humidityLowerPct,
        double humidityUpperPct,
        @Schema(description = "Threshold version that was current when the request was made")
        long baseThresholdId,
        String requestedBy,
        Instant requestedAt,
        @Schema(description = "Who approved, rejected, cancelled, or superseded it; null while pending or when the "
                + "system superseded it (new storage session)")
        String reviewedBy,
        Instant reviewedAt,
        String reviewNote,
        @Schema(description = "Threshold version created by the approval; null unless APPROVED")
        Long appliedThresholdId) {

    public static ThresholdRequestResponse from(ThresholdChangeRequest request) {
        return new ThresholdRequestResponse(request.id(), request.deviceId(), request.status(),
                request.limits().temperatureLowerC(), request.limits().temperatureUpperC(),
                request.limits().humidityLowerPct(), request.limits().humidityUpperPct(), request.baseThresholdId(),
                request.requestedByUsername(), request.requestedAt(), request.reviewedByUsername(),
                request.reviewedAt(), request.reviewNote(), request.appliedThresholdId());
    }
}
