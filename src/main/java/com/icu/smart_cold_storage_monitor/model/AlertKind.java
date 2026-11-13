package com.icu.smart_cold_storage_monitor.model;

/** What raised an alert. Both kinds share the {@code alerts} table, the severity scale, and acknowledgment. */
public enum AlertKind {
    /** A temperature or humidity value outside the session's limits (FR-04). */
    THRESHOLD,
    /** A risk level of MEDIUM or above from the risk predictor (FR-05). */
    RISK
}
