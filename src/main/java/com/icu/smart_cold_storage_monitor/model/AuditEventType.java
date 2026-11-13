package com.icu.smart_cold_storage_monitor.model;

/**
 * Audit event catalog (design doc §7, NIST AU-2). Further types such as alert acknowledgments and storage-session
 * changes are added with their features.
 */
public enum AuditEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    ACCESS_DENIED,
    USER_CREATED,
    PASSWORD_RESET,
    USER_DEACTIVATED,
    STORAGE_SESSION_STARTED,
    /** A user made new threshold limits current (directly as administrator, or by approving a request). */
    THRESHOLD_CHANGED,
    /** An operator proposed new threshold limits for administrator review. */
    THRESHOLD_CHANGE_REQUESTED,
    THRESHOLD_CHANGE_APPROVED,
    THRESHOLD_CHANGE_REJECTED,
    THRESHOLD_CHANGE_CANCELLED
}
