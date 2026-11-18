package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.time.Duration;
import java.time.Instant;

/** A chronological accumulator owned by one storage session. */
public final class RiskFeatures {

    static final Duration MAX_OBSERVED_INTERVAL = Duration.ofSeconds(30);

    public enum GapReason {
        NONE, RUNNING_DATA_GAP, ORDERLY_MONITORING_INTERRUPTION, UNCONFIRMED_MONITORING_INTERRUPTION,
        MONITORING_INTERRUPTION_WITH_RUNNING_GAP, UNOBSERVED_PREFIX, MISSING_THRESHOLD, LEGACY_UNATTRIBUTED
    }

    public record Snapshot(double temperatureC, double humidityPct, double observedAboveUpperSeconds,
                           double elapsedStorageSeconds, String productType, boolean incompleteHistory,
                           GapReason currentIssue, GapReason lastGapReason) {
        public Snapshot {
            if (currentIssue == null) {
                currentIssue = incompleteHistory ? GapReason.LEGACY_UNATTRIBUTED : GapReason.NONE;
            }
            if (lastGapReason == null) {
                lastGapReason = incompleteHistory ? GapReason.LEGACY_UNATTRIBUTED : GapReason.NONE;
            }
        }

        public Snapshot(double temperatureC, double humidityPct, double observedAboveUpperSeconds,
                        double elapsedStorageSeconds, String productType, boolean incompleteHistory) {
            this(temperatureC, humidityPct, observedAboveUpperSeconds, elapsedStorageSeconds, productType,
                    incompleteHistory, null, null);
        }
    }

    private final Instant startedAt;
    private final String productType;
    private Instant previousAt;
    private double previousTemperature;
    private ThresholdLimits previousLimits;
    private Duration exposure = Duration.ZERO;
    private GapReason lastGapReason = GapReason.NONE;

    public RiskFeatures(Instant startedAt, String productType) {
        this.startedAt = startedAt;
        this.productType = productType;
    }

    static RiskFeatures resume(Instant startedAt, Snapshot checkpoint, Instant checkpointAt,
                               ThresholdLimits checkpointLimits) {
        var features = new RiskFeatures(startedAt, checkpoint.productType());
        features.previousAt = checkpointAt;
        features.previousTemperature = checkpoint.temperatureC();
        features.previousLimits = checkpointLimits;
        // The reading store uses millisecond timestamps. Restore the exact accumulated duration.
        features.exposure = Duration.ofMillis(Math.round(checkpoint.observedAboveUpperSeconds() * 1000));
        features.lastGapReason = checkpoint.lastGapReason();
        return features;
    }

    /** Limits are the version recorded on this reading. Null means no known version. */
    public Snapshot accept(Instant at, double temperatureC, double humidityPct, ThresholdLimits limits) {
        return accept(at, temperatureC, humidityPct, limits, GapReason.NONE);
    }

    Instant previousAt() {
        return previousAt;
    }

    Snapshot accept(Instant at, double temperatureC, double humidityPct, ThresholdLimits limits, GapReason interruption) {
        if (at.isBefore(startedAt) || previousAt != null && !at.isAfter(previousAt)) {
            throw new IllegalArgumentException("Readings must be in session and strictly chronological.");
        }
        if (!Double.isFinite(temperatureC) || !Double.isFinite(humidityPct) || humidityPct < 0 || humidityPct > 100) {
            throw new IllegalArgumentException("Invalid temperature or humidity.");
        }

        GapReason issue = interruption;
        if (issue == GapReason.NONE && previousAt == null
                && Duration.between(startedAt, at).compareTo(MAX_OBSERVED_INTERVAL) > 0) {
            issue = GapReason.UNOBSERVED_PREFIX;
        } else if (issue == GapReason.NONE && previousAt != null) {
            Duration interval = Duration.between(previousAt, at);
            if (interval.compareTo(MAX_OBSERVED_INTERVAL) > 0) {
                issue = GapReason.RUNNING_DATA_GAP;
            } else if (previousLimits == null) {
                issue = GapReason.MISSING_THRESHOLD;
            } else if (previousTemperature > previousLimits.temperatureUpperC()) {
                exposure = exposure.plus(interval);
            }
        }
        if (issue == GapReason.NONE && limits == null) {
            issue = GapReason.MISSING_THRESHOLD;
        }
        if (issue != GapReason.NONE) {
            lastGapReason = issue;
        }
        previousAt = at;
        previousTemperature = temperatureC;
        previousLimits = limits;
        return new Snapshot(temperatureC, humidityPct, seconds(exposure),
                seconds(Duration.between(startedAt, at)), productType, lastGapReason != GapReason.NONE, issue, lastGapReason);
    }

    private static double seconds(Duration duration) {
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }
}
