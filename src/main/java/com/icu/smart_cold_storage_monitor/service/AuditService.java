package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Writes audit events.
 *
 * <p>This service joins the caller's transaction when there is one, so a business change and its audit event
 * commit or roll back together; an audit failure propagates and rejects the change. Authentication handlers call
 * it outside any business transaction, so their events are written independently.
 */
@Service
public class AuditService {

    /** Bound for usernames typed into a failed login, matching the column constraint. */
    public static final int MAX_ATTEMPTED_USERNAME_LENGTH = 64;
    static final int MAX_TEXT_LENGTH = 500;

    private final AuditEventRepository repository;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void recordLoginSuccess(long userId) {
        record(userId, null, AuditEventType.LOGIN_SUCCESS, AuditResult.SUCCESS, "USER", String.valueOf(userId), null);
    }

    /** Records a failed login with the bounded attempted username. The password is never passed in. */
    public void recordLoginFailure(String attemptedUsername, String reason) {
        record(null, sanitizeAttemptedUsername(attemptedUsername), AuditEventType.LOGIN_FAILURE, AuditResult.FAILURE,
                "USER", null, reason);
    }

    public void recordLogout(long userId) {
        record(userId, null, AuditEventType.LOGOUT, AuditResult.SUCCESS, "USER", String.valueOf(userId), null);
    }

    public void recordAccessDenied(Long userId, String endpoint, String reason) {
        record(userId, null, AuditEventType.ACCESS_DENIED, AuditResult.DENIED, "ENDPOINT", endpoint, reason);
    }

    public void recordUserCreated(Long actorId, long createdUserId, String details) {
        record(actorId, null, AuditEventType.USER_CREATED, AuditResult.SUCCESS, "USER",
                String.valueOf(createdUserId), details);
    }

    /** Records a password reset. The new password is never included. */
    public void recordPasswordReset(long actorId, long targetUserId, String details) {
        record(actorId, null, AuditEventType.PASSWORD_RESET, AuditResult.SUCCESS, "USER",
                String.valueOf(targetUserId), details);
    }

    public void recordUserDeactivated(long actorId, long targetUserId, String details) {
        record(actorId, null, AuditEventType.USER_DEACTIVATED, AuditResult.SUCCESS, "USER",
                String.valueOf(targetUserId), details);
    }

    private void record(Long actorId, String attemptedUsername, AuditEventType type, AuditResult result,
                        String targetType, String targetId, String details) {
        Instant now = Instant.now(clock);
        repository.insert(new AuditEvent(null, actorId, attemptedUsername, type, now, result, targetType,
                bound(targetId), bound(details)));
    }

    /**
     * Strips control characters and truncates to {@link #MAX_ATTEMPTED_USERNAME_LENGTH} so arbitrary client input
     * cannot bloat or corrupt audit records. Returns {@code null} for missing or blank input.
     */
    static String sanitizeAttemptedUsername(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= MAX_ATTEMPTED_USERNAME_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_ATTEMPTED_USERNAME_LENGTH);
    }

    private static String bound(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH);
    }
}
