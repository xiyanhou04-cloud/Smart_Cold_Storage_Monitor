package com.icu.smart_cold_storage_monitor.model;

import java.time.Instant;

/**
 * One threshold version of a storage session (row of {@code threshold_settings}).
 *
 * @param changedBy         the user who made this version current, or {@code null} for the system default a
 *                          session starts with
 * @param changedByUsername that user's current username, or {@code null}
 */
public record ThresholdSetting(long id, long sessionId, ThresholdLimits limits, Instant effectiveFrom, Long changedBy,
                               String changedByUsername) {

    /** True for the location default the system assigned when the session started. */
    public boolean systemDefault() {
        return changedBy == null;
    }
}
