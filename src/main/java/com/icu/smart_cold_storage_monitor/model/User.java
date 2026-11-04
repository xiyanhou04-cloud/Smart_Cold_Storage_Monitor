package com.icu.smart_cold_storage_monitor.model;

import java.time.Instant;

/** A row of the {@code users} table. */
public record User(long id, String username, String passwordHash, Role role, boolean enabled, Instant createdAt) {
}
