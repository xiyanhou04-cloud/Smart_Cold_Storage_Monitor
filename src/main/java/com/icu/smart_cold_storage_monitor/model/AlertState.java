package com.icu.smart_cold_storage_monitor.model;

/** Whether an alert's condition is still going on. Independent of acknowledgment. */
public enum AlertState {
    /** Not resolved yet ({@code resolved_at IS NULL}). */
    ACTIVE,
    RESOLVED
}
