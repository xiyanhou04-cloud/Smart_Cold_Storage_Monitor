package com.icu.smart_cold_storage_monitor.model;

/**
 * The shared risk scale, in increasing order. Predictions use all three levels; an alert's severity is MEDIUM or
 * HIGH, since LOW never raises an alert.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
