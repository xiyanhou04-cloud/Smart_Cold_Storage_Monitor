package com.icu.smart_cold_storage_monitor.model;

/** User roles enforced by the backend. Only ADMIN manages users, sessions, thresholds, and audit review. */
public enum Role {
    OPERATOR,
    ADMIN;

    /** Spring Security authority name, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
