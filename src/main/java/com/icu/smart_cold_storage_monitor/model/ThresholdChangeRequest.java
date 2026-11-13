package com.icu.smart_cold_storage_monitor.model;

import java.time.Instant;

/**
 * An operator's proposed threshold change for one device (row of {@code threshold_change_requests}), with the
 * current usernames of the requester and reviewer.
 *
 * @param baseThresholdId    the version that was current when the request was made
 * @param reviewedBy         the administrator who approved, rejected, or superseded it; the requester for a
 *                           cancellation or a replacement by their newer request; {@code null} while pending or when
 *                           the system superseded it
 * @param appliedThresholdId the version an approval created, otherwise {@code null}
 */
public record ThresholdChangeRequest(long id, String deviceId, long baseThresholdId, ThresholdLimits limits,
                                     ThresholdRequestStatus status, long requestedBy, String requestedByUsername,
                                     Instant requestedAt, Long reviewedBy, String reviewedByUsername,
                                     Instant reviewedAt, String reviewNote, Long appliedThresholdId) {

    public boolean pending() {
        return status == ThresholdRequestStatus.PENDING;
    }
}
