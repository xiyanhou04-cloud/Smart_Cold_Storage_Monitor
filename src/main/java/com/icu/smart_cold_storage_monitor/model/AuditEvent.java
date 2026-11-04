package com.icu.smart_cold_storage_monitor.model;

import java.time.Instant;

/**
 * A row of the append-only {@code audit_events} table.
 *
 * @param id                database id, or {@code null} before insertion
 * @param actorId           authenticated user, or {@code null} (e.g. failed login, system setup)
 * @param attemptedUsername bounded username typed in a failed login; never a password
 */
public record AuditEvent(
        Long id,
        Long actorId,
        String attemptedUsername,
        AuditEventType eventType,
        Instant occurredAt,
        AuditResult result,
        String targetType,
        String targetId,
        String details) {
}
