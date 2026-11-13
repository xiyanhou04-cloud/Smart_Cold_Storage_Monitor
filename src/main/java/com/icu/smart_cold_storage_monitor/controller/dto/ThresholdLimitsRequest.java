package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/settings/thresholds/{deviceId}}: all four limits, replacing the current ones. Each lower
 * limit must be below its upper limit; a reading equal to a limit is in range.
 */
public record ThresholdLimitsRequest(
        @Schema(description = "Lower temperature limit, °C", example = "0.0")
        @NotNull @DecimalMin("-40") @DecimalMax("125") Double temperatureLowerC,
        @Schema(description = "Upper temperature limit, °C", example = "4.0")
        @NotNull @DecimalMin("-40") @DecimalMax("125") Double temperatureUpperC,
        @Schema(description = "Lower humidity limit, %RH", example = "55.0")
        @NotNull @DecimalMin("0") @DecimalMax("100") Double humidityLowerPct,
        @Schema(description = "Upper humidity limit, %RH", example = "80.0")
        @NotNull @DecimalMin("0") @DecimalMax("100") Double humidityUpperPct) {

    public ThresholdLimits toLimits() {
        return new ThresholdLimits(temperatureLowerC, temperatureUpperC, humidityLowerPct, humidityUpperPct);
    }
}
