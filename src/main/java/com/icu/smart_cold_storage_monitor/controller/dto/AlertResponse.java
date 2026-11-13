package com.icu.smart_cold_storage_monitor.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertDirection;
import com.icu.smart_cold_storage_monitor.model.AlertKind;
import com.icu.smart_cold_storage_monitor.model.AlertResolveReason;
import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.AlertState;
import com.icu.smart_cold_storage_monitor.model.ReadingMetric;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One alert in the format shared by threshold and risk alerts. Exactly one of {@code threshold} (kind THRESHOLD)
 * and {@code risk} (kind RISK) is present; {@code resolvedAt}, {@code resolveReason} and {@code acknowledgment} are
 * {@code null} until the alert is resolved or acknowledged.
 */
public record AlertResponse(
        long id,
        String deviceId,
        long sessionId,
        AlertKind kind,
        RiskLevel severity,
        AlertSource source,
        AlertState state,
        @Schema(description = "Measured time of the reading that opened the alert") Instant openedAt,
        @Schema(description = "Measured time of the latest reading that confirmed it") Instant lastSeenAt,
        @Schema(description = "Measured time of the reading that ended it") Instant resolvedAt,
        AlertResolveReason resolveReason,
        @JsonInclude(JsonInclude.Include.NON_NULL) ThresholdDetails threshold,
        @JsonInclude(JsonInclude.Include.NON_NULL) RiskDetails risk,
        Acknowledgment acknowledgment) {

    /** The excursion of a threshold alert; values are in {@code unit}. */
    public record ThresholdDetails(ReadingMetric metric, AlertDirection direction,
                                   @Schema(example = "°C") String unit,
                                   @Schema(description = "The crossed limit of the opening threshold version")
                                   double limit,
                                   double openingValue,
                                   double lastValue,
                                   @Schema(description = "Furthest beyond the limit so far") double peakValue,
                                   long thresholdId) {
    }

    public record RiskDetails(String modelOrRuleVersion, String qualityFlag) {
    }

    /** Who acknowledged the alert, when (server time), and their note. */
    public record Acknowledgment(long userId, String username, Instant at, String note) {
    }

    public static AlertResponse from(Alert alert) {
        ThresholdDetails threshold = alert.kind() == AlertKind.THRESHOLD
                ? new ThresholdDetails(alert.metric(), alert.direction(), unit(alert.metric()), alert.limitValue(),
                        alert.openingValue(), alert.lastValue(), alert.peakValue(), alert.openingThresholdId())
                : null;
        RiskDetails risk = alert.kind() == AlertKind.RISK
                ? new RiskDetails(alert.modelOrRuleVersion(), alert.qualityFlag())
                : null;
        Acknowledgment acknowledgment = alert.acknowledged()
                ? new Acknowledgment(alert.acknowledgedBy(), alert.acknowledgedByUsername(), alert.acknowledgedAt(),
                        alert.note())
                : null;
        return new AlertResponse(alert.id(), alert.deviceId(), alert.sessionId(), alert.kind(), alert.severity(),
                alert.source(), alert.state(), alert.openedAt(), alert.lastSeenAt(), alert.resolvedAt(),
                alert.resolveReason(), threshold, risk, acknowledgment);
    }

    private static String unit(ReadingMetric metric) {
        return metric == ReadingMetric.TEMPERATURE ? "°C" : "%RH";
    }
}
