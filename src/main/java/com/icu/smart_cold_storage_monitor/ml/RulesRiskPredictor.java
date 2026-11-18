package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class RulesRiskPredictor implements RiskPredictor {

    private final RiskRules rules;

    public RulesRiskPredictor(@Value("${app.risk.medium-after-seconds:120}") long medium,
                              @Value("${app.risk.high-after-seconds:600}") long high) {
        this.rules = new RiskRules(medium, high);
    }

    RiskRules configuration() {
        return rules;
    }

    @Override
    public Estimate predict(RiskFeatures.Snapshot features, ThresholdLimits currentLimits) {
        return fallback(features, currentLimits, Quality.from(features.currentIssue()));
    }

    Estimate fallback(RiskFeatures.Snapshot features, ThresholdLimits currentLimits, Quality quality) {
        return new Estimate(rules.predict(features, currentLimits, features.currentIssue() != RiskFeatures.GapReason.NONE),
                AlertSource.RULES, RiskRules.VERSION, quality);
    }
}
