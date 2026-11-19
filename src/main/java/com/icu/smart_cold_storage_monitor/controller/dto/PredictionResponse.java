package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.Prediction;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One risk prediction with the reading it scored. {@code features} holds the model inputs that are not on the
 * reading itself; its fields, and the gap fields, are {@code null} when the stored snapshot lacks them.
 */
public record PredictionResponse(
        long id,
        long readingId,
        String deviceId,
        long sessionId,
        @Schema(description = "Measured time of the scored reading") Instant measuredAt,
        @Schema(example = "4.6") double temperatureC,
        @Schema(example = "70.0") double humidityPct,
        @Schema(description = "Threshold version the reading recorded, or null") Long thresholdId,
        RiskLevel riskLevel,
        @Schema(description = "MODEL or RULES", allowableValues = {"MODEL", "RULES"}) AlertSource source,
        @Schema(example = "random-forest-ml2-v1") String modelOrRuleVersion,
        @Schema(description = "Server time the prediction was computed") Instant predictedAt,
        @Schema(description = "OK, or why the rules were used", example = "OK") String qualityFlag,
        Features features,
        @Schema(description = "The session had a gap before this reading, so the observed exposure is a lower bound")
        Boolean historicalExposureIncomplete,
        @Schema(description = "Gap that ended at this reading (NONE if none); null for older predictions",
                example = "NONE")
        String currentGapReason,
        @Schema(description = "Most recent gap in the session up to this reading (NONE if none)", example = "NONE")
        String lastGapReason) {

    public record Features(
            @Schema(description = "Observed seconds above the upper temperature limit") Double observedAboveUpperSeconds,
            @Schema(description = "Seconds since the storage session started") Double elapsedStorageSeconds,
            @Schema(example = "RAW_MEAT") String productType) {
    }

    public static PredictionResponse from(Prediction p) {
        return new PredictionResponse(p.id(), p.readingId(), p.deviceId(), p.sessionId(), p.measuredAt(),
                p.temperatureC(), p.humidityPct(), p.thresholdId(), p.riskLevel(), p.source(),
                p.modelOrRuleVersion(), p.predictedAt(), p.qualityFlag(),
                new Features(p.observedAboveUpperSeconds(), p.elapsedStorageSeconds(), p.productType()),
                p.historicalExposureIncomplete(), p.currentGapReason(), p.lastGapReason());
    }
}
