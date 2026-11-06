package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import java.time.Instant;
import java.util.Set;

/**
 * Criteria for audit review queries. Every criterion is optional; {@code null} or empty means "no restriction".
 *
 * @param from       inclusive lower bound on {@code occurred_at}
 * @param to         exclusive upper bound on {@code occurred_at}
 * @param actor      exact username of the acting user, or the attempted username of a failed login
 * @param eventTypes event types to include
 * @param results    outcomes to include
 */
public record AuditEventFilter(
        Instant from,
        Instant to,
        String actor,
        Set<AuditEventType> eventTypes,
        Set<AuditResult> results) {

    public AuditEventFilter {
        eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
        results = results == null ? Set.of() : Set.copyOf(results);
    }

    public static AuditEventFilter none() {
        return new AuditEventFilter(null, null, null, Set.of(), Set.of());
    }
}
