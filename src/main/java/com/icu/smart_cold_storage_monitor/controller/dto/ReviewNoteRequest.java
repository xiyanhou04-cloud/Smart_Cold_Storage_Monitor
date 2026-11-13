package com.icu.smart_cold_storage_monitor.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Optional body of a threshold request approval or rejection. */
public record ReviewNoteRequest(
        @Schema(description = "Reviewer note shown to the requester", example = "Matches the new supplier spec.")
        @Size(max = 500) String note) {
}
