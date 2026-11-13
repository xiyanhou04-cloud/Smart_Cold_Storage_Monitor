package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingSubmission;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * One sensor reading posted by a device. JSON cannot carry NaN or infinity, so numbers are finite once parsed.
 * The temperature range is the SHT45 measurement range (plausibility), not the storage safe range.
 */
public record ReadingRequest(
        @Schema(example = "ESP32-SIM-001") @NotBlank @Size(max = 64) String deviceId,
        @Schema(example = "1") @NotNull @Positive Long sessionId,
        @Schema(description = "UTC measurement time, at most 60 s ahead of server time; stored to the millisecond",
                example = "2026-11-12T08:00:05.123Z") @NotNull Instant timestamp,
        @Schema(example = "4.0032") @NotNull @DecimalMin("-40") @DecimalMax("125") Double temperatureC,
        @Schema(example = "69.99565") @NotNull @DecimalMin("0") @DecimalMax("100") Double humidityPct) {

    public ReadingSubmission toSubmission() {
        return new ReadingSubmission(deviceId, sessionId, timestamp, temperatureC, humidityPct);
    }
}
