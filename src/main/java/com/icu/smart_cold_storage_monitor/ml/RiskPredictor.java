package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;

public interface RiskPredictor {
    enum Quality {
        OK, MODEL_MISSING, MODEL_INCOMPATIBLE, MODEL_ERROR, UNKNOWN_PRODUCT, INCOMPLETE_HISTORY,
        MONITORING_INTERRUPTED, MONITORING_INTERRUPTION_UNKNOWN;

        static Quality from(RiskFeatures.GapReason issue) {
            return switch (issue) {
                case NONE -> OK;
                case ORDERLY_MONITORING_INTERRUPTION, MONITORING_INTERRUPTION_WITH_RUNNING_GAP -> MONITORING_INTERRUPTED;
                case UNCONFIRMED_MONITORING_INTERRUPTION -> MONITORING_INTERRUPTION_UNKNOWN;
                default -> INCOMPLETE_HISTORY;
            };
        }
    }

    record Estimate(RiskLevel level, AlertSource source, String version, Quality quality) {}

    Estimate predict(RiskFeatures.Snapshot features, ThresholdLimits currentLimits);
}
