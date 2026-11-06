package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.AuditEntry;
import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import java.time.Instant;

/**
 * One audit record in administrator review. {@code actorId}/{@code actorUsername} identify the authenticated user;
 * {@code attemptedUsername} is set only for failed logins. Fields without a value are {@code null}.
 */
public record AuditEventResponse(
        long id,
        Instant occurredAt,
        AuditEventType eventType,
        AuditResult result,
        Long actorId,
        String actorUsername,
        String attemptedUsername,
        String targetType,
        String targetId,
        String details) {

    public static AuditEventResponse from(AuditEntry entry) {
        AuditEvent e = entry.event();
        return new AuditEventResponse(e.id(), e.occurredAt(), e.eventType(), e.result(), e.actorId(),
                entry.actorUsername(), e.attemptedUsername(), e.targetType(), e.targetId(), e.details());
    }
}
