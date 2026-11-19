package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.time.Instant;
import java.util.Set;

/**
 * Criteria for prediction history queries. Every criterion is optional; {@code null} or empty means "no
 * restriction".
 *
 * @param deviceIds  devices to include
 * @param riskLevels risk levels to include
 * @param from       inclusive lower bound on the scored reading's {@code measured_at}
 * @param to         exclusive upper bound on the scored reading's {@code measured_at}
 */
public record PredictionFilter(Set<String> deviceIds, Set<RiskLevel> riskLevels, Instant from, Instant to) {

    public PredictionFilter {
        deviceIds = deviceIds == null ? Set.of() : Set.copyOf(deviceIds);
        riskLevels = riskLevels == null ? Set.of() : Set.copyOf(riskLevels);
    }
}
