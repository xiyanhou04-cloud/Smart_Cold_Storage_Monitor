package com.icu.smart_cold_storage_monitor.model;

import java.time.Instant;

/** A storage session of one device (row of {@code storage_sessions}); active while {@code endedAt} is null. */
public record StorageSession(long id, String deviceId, String productType, Instant startedAt, Instant endedAt) {

    public boolean active() {
        return endedAt == null;
    }
}
