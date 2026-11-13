package com.icu.smart_cold_storage_monitor.model;

/** Lifecycle of a threshold change request; only {@link #PENDING} requests can change state. */
public enum ThresholdRequestStatus {
    /** Waiting for an administrator. At most one per device. */
    PENDING,
    /** An administrator applied the proposed limits as a new threshold version. */
    APPROVED,
    /** An administrator declined the request. */
    REJECTED,
    /** The requester withdrew the request. */
    CANCELLED,
    /**
     * Replaced before review: by a newer request from the same requester, by an administrator setting the device's
     * limits directly, or by a new storage session that reset the limits to the default.
     */
    SUPERSEDED
}
