package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.ml.RiskFeatures.Snapshot;

/** Simulation policy used to label the demo data and score the rules baseline. */
public record RiskRules(long mediumAfterSeconds, long highAfterSeconds) {

    public static final String VERSION = "simulated-exposure-v1";

    public RiskRules {
        if (mediumAfterSeconds <= 0 || highAfterSeconds <= mediumAfterSeconds) {
            throw new IllegalArgumentException("Expected 0 < mediumAfterSeconds < highAfterSeconds.");
        }
    }

    public RiskLevel predict(Snapshot snapshot, ThresholdLimits limits) {
        return predict(snapshot, limits, snapshot.incompleteHistory());
    }

    RiskLevel predict(Snapshot snapshot, ThresholdLimits limits, boolean missingInterval) {
        if (snapshot.observedAboveUpperSeconds() >= highAfterSeconds) {
            return RiskLevel.HIGH;
        }
        if (snapshot.observedAboveUpperSeconds() >= mediumAfterSeconds || missingInterval
                || limits == null || snapshot.temperatureC() < limits.temperatureLowerC()
                || snapshot.temperatureC() > limits.temperatureUpperC()) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }
}
