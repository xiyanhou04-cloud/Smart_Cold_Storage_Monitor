package com.icu.smart_cold_storage_monitor.model;

import java.time.Instant;

/**
 * One alert episode (row of {@code alerts}). Timestamps are the measured times of the readings that opened, last
 * confirmed, and ended the episode. The threshold fields are set for {@link AlertKind#THRESHOLD} and null for
 * {@link AlertKind#RISK}; the model fields the other way round.
 *
 * @param openingThresholdId the threshold version the opening reading was checked against
 * @param limitValue         the limit of that version the opening value crossed
 * @param peakValue          the value furthest beyond the limit so far (highest above, lowest below)
 * @param acknowledgedBy     the user who acknowledged the alert (FR-06), or {@code null}
 * @param acknowledgedByUsername that user's current username, or {@code null}
 */
public record Alert(long id, long sessionId, String deviceId, AlertKind kind, RiskLevel severity, AlertSource source,
                    Instant openedAt, Instant lastSeenAt, Instant resolvedAt, AlertResolveReason resolveReason,
                    ReadingMetric metric, AlertDirection direction, Long openingThresholdId, Double limitValue,
                    Double openingValue, Double lastValue, Double peakValue,
                    String modelOrRuleVersion, String qualityFlag,
                    Long acknowledgedBy, String acknowledgedByUsername, Instant acknowledgedAt, String note) {

    public boolean active() {
        return resolvedAt == null;
    }

    public AlertState state() {
        return active() ? AlertState.ACTIVE : AlertState.RESOLVED;
    }

    public boolean acknowledged() {
        return acknowledgedAt != null;
    }
}
