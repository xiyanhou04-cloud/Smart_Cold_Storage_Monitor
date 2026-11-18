package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.time.Duration;
import java.time.Instant;

/** A chronological accumulator owned by one storage session. */
public final class RiskFeatures {

    static final Duration MAX_OBSERVED_INTERVAL = Duration.ofSeconds(30);

    public record Snapshot(double temperatureC, double humidityPct, double observedAboveUpperSeconds,
                           double elapsedStorageSeconds, String productType, boolean incompleteHistory) {}

    private final Instant startedAt;
    private final String productType;
    private Instant previousAt;
    private double previousTemperature;
    private ThresholdLimits previousLimits;
    private Duration exposure = Duration.ZERO;
    private boolean incompleteHistory;

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
        features.incompleteHistory = checkpoint.incompleteHistory();
        return features;
    }

    /** Limits are the version recorded on this reading. Null means no known version. */
    public Snapshot accept(Instant at, double temperatureC, double humidityPct, ThresholdLimits limits) {
        if (at.isBefore(startedAt) || previousAt != null && !at.isAfter(previousAt)) {
            throw new IllegalArgumentException("Readings must be in session and strictly chronological.");
        }
        if (!Double.isFinite(temperatureC) || !Double.isFinite(humidityPct) || humidityPct < 0 || humidityPct > 100) {
            throw new IllegalArgumentException("Invalid temperature or humidity.");
        }

        if (previousAt == null) {
            incompleteHistory = Duration.between(startedAt, at).compareTo(MAX_OBSERVED_INTERVAL) > 0;
        } else {
            Duration interval = Duration.between(previousAt, at);
            if (interval.compareTo(MAX_OBSERVED_INTERVAL) > 0 || previousLimits == null) {
                incompleteHistory = true;
            } else if (previousTemperature > previousLimits.temperatureUpperC()) {
                exposure = exposure.plus(interval);
            }
        }
        incompleteHistory |= limits == null;
        previousAt = at;
        previousTemperature = temperatureC;
        previousLimits = limits;
        return new Snapshot(temperatureC, humidityPct, seconds(exposure),
                seconds(Duration.between(startedAt, at)), productType, incompleteHistory);
    }

    private static double seconds(Duration duration) {
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }
}
