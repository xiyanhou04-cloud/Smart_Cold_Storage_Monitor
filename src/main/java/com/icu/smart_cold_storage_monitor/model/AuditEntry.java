package com.icu.smart_cold_storage_monitor.model;

/**
 * An audit event as shown in administrator review: the stored row plus the acting user's current username.
 *
 * @param actorUsername username of {@link AuditEvent#actorId()}, or {@code null} when there is no actor
 */
public record AuditEntry(AuditEvent event, String actorUsername) {
}
