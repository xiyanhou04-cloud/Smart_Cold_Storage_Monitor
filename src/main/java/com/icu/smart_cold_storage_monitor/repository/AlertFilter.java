package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.AlertKind;
import com.icu.smart_cold_storage_monitor.model.AlertState;
import java.time.Instant;
import java.util.Set;

/**
 * Criteria for alert history queries. Every criterion is optional; {@code null} or empty means "no restriction".
 *
 * @param deviceIds    devices to include
 * @param kinds        alert kinds to include
 * @param state        active or resolved alerts only
 * @param acknowledged acknowledged ({@code true}) or unacknowledged ({@code false}) alerts only
 * @param from         inclusive lower bound on {@code opened_at}
 * @param to           exclusive upper bound on {@code opened_at}
 */
public record AlertFilter(Set<String> deviceIds, Set<AlertKind> kinds, AlertState state, Boolean acknowledged,
                          Instant from, Instant to) {

    public AlertFilter {
        deviceIds = deviceIds == null ? Set.of() : Set.copyOf(deviceIds);
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
    }

    public static AlertFilter none() {
        return new AlertFilter(Set.of(), Set.of(), null, null, null, null);
    }
}
