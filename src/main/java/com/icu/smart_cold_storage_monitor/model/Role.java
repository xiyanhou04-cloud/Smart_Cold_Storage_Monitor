package com.icu.smart_cold_storage_monitor.model;

/**
 * User roles enforced by the backend. Only ADMIN manages users, sessions, and audit review, and sets thresholds
 * directly; an OPERATOR's threshold change is a request that an ADMIN approves or rejects.
 */
public enum Role {
    OPERATOR,
    ADMIN;

    /** Spring Security authority name, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
