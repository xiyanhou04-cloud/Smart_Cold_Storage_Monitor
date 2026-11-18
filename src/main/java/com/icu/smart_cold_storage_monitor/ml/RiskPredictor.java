package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;

public interface RiskPredictor {
    enum Quality { OK, MODEL_MISSING, MODEL_INCOMPATIBLE, MODEL_ERROR, UNKNOWN_PRODUCT, INCOMPLETE_HISTORY }

    record Estimate(RiskLevel level, AlertSource source, String version, Quality quality) {}

    Estimate predict(RiskFeatures.Snapshot features, ThresholdLimits currentLimits);
}
