package com.icu.smart_cold_storage_monitor.model;

import java.time.Instant;

/**
 * One stored risk prediction (row of {@code predictions}) with the reading it scored. The feature and gap fields
 * come from the stored feature snapshot and are {@code null} when the snapshot lacks them (predictions written
 * before gap reasons existed have no {@code currentGapReason}).
 *
 * @param measuredAt                   measured time of the scored reading
 * @param predictedAt                  server time the prediction was computed
 * @param qualityFlag                  {@code OK} or why the rules were used ({@code RiskPredictor.Quality})
 * @param observedAboveUpperSeconds    observed time above the upper temperature limit up to this reading
 * @param elapsedStorageSeconds        time since the storage session started
 * @param productType                  product category the model was given
 * @param historicalExposureIncomplete the session had a gap before this reading, so exposure is a lower bound
 * @param currentGapReason             the gap that ended at this reading, {@code NONE} if there was none
 * @param lastGapReason                the session's most recent gap up to this reading, {@code NONE} if none
 */
public record Prediction(long id, long readingId, String deviceId, long sessionId, Instant measuredAt,
                         double temperatureC, double humidityPct, Long thresholdId,
                         RiskLevel riskLevel, AlertSource source, String modelOrRuleVersion, Instant predictedAt,
                         String qualityFlag, Double observedAboveUpperSeconds, Double elapsedStorageSeconds,
                         String productType, Boolean historicalExposureIncomplete, String currentGapReason,
                         String lastGapReason) {
}
