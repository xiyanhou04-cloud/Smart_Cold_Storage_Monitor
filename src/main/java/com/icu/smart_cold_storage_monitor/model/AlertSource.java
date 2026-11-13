package com.icu.smart_cold_storage_monitor.model;

/** Where an alert's evidence came from: a threshold check, the trained model, or the rules fallback. */
public enum AlertSource {
    THRESHOLD,
    MODEL,
    RULES
}
