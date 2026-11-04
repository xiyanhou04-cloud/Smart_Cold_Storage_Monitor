package com.icu.smart_cold_storage_monitor.model;

/**
 * Audit event catalog (design doc §7, NIST AU-2). Further types such as threshold changes, alert
 * acknowledgments, and storage-session changes are added with their features.
 */
public enum AuditEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    ACCESS_DENIED,
    USER_CREATED,
    PASSWORD_RESET,
    USER_DEACTIVATED
}
