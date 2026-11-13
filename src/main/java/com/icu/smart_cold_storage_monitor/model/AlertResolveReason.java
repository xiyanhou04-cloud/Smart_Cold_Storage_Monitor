package com.icu.smart_cold_storage_monitor.model;

/** Why an alert ended. */
public enum AlertResolveReason {
    /** A reading confirmed the condition ended (back in range, or risk back to LOW). */
    RECOVERED,
    /** Replaced by a new alert of the same kind, e.g. a value jumping from below the lower to above the upper limit. */
    SUPERSEDED,
    /** The storage session ended while the alert was active. */
    SESSION_ENDED
}
