package com.icu.smart_cold_storage_monitor.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Optional body of an alert acknowledgment. */
public record AcknowledgmentRequest(
        @Schema(description = "What was done about the alert; stripped, blank means no note",
                example = "Door was left open; closed it and checked the product.")
        @Size(max = 500) String note) {
}
