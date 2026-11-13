package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.service.AlertAcknowledgmentService.AcknowledgmentOutcome;
import com.icu.smart_cold_storage_monitor.service.AlertAcknowledgmentService.AcknowledgmentResult;

/** Result of an acknowledgment: whether this call recorded it, and the alert with its acknowledgment. */
public record AcknowledgmentResponse(AcknowledgmentOutcome outcome, AlertResponse alert) {

    public static AcknowledgmentResponse from(AcknowledgmentResult result) {
        return new AcknowledgmentResponse(result.outcome(), AlertResponse.from(result.alert()));
    }
}
