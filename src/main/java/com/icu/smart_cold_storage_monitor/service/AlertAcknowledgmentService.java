package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertKind;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import com.icu.smart_cold_storage_monitor.service.exception.NotFoundException;
import com.icu.smart_cold_storage_monitor.service.exception.OperationNotPermittedException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alert acknowledgment (FR-06) and its audit event (FR-07).
 *
 * <p>An active OPERATOR or ADMIN records that they responded to an alert, optionally with a note of up to
 * {@value #MAX_NOTE_LENGTH} characters (stripped; blank means none). Acknowledgment records the user and the server
 * time and changes nothing else: an active alert stays active and the threshold state machine keeps updating and
 * resolving it. Resolved alerts may be acknowledged too, since a response may come after the excursion ended.
 *
 * <p>The acknowledgment and its {@code ALERT_ACKNOWLEDGED} audit event are written in one transaction; an audit
 * failure rolls the acknowledgment back. The first acknowledgment is final: a repeated one, by any user and with any
 * note, returns the existing acknowledgment unchanged and writes no audit event.
 */
@Service
public class AlertAcknowledgmentService {

    public static final int MAX_NOTE_LENGTH = 500;

    private final AlertRepository alerts;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;

    public AlertAcknowledgmentService(AlertRepository alerts, UserRepository users, AuditService audit, Clock clock) {
        this.alerts = alerts;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    public enum AcknowledgmentOutcome {
        /** This call acknowledged the alert. */
        ACKNOWLEDGED,
        /** The alert had already been acknowledged; the existing acknowledgment is returned. */
        ALREADY_ACKNOWLEDGED
    }

    public record AcknowledgmentResult(AcknowledgmentOutcome outcome, Alert alert) {
    }

    @Transactional
    public AcknowledgmentResult acknowledge(long actorId, long alertId, String note) {
        requireActiveUser(actorId);
        String cleanNote = validateNote(note);
        Alert alert = alerts.findById(alertId)
                .orElseThrow(() -> new NotFoundException("Alert " + alertId + " does not exist."));
        if (alert.acknowledged()) {
            return new AcknowledgmentResult(AcknowledgmentOutcome.ALREADY_ACKNOWLEDGED, alert);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        // The transaction holds SQLite's write lock (BEGIN IMMEDIATE), so nobody can acknowledge in between; the
        // conditional update is a second guard.
        Alert acknowledged = alerts.acknowledge(alertId, actorId, now, cleanNote).orElse(null);
        if (acknowledged == null) {
            return new AcknowledgmentResult(AcknowledgmentOutcome.ALREADY_ACKNOWLEDGED,
                    alerts.findById(alertId).orElseThrow());
        }
        audit.recordAlertAcknowledged(actorId, alertId, details(acknowledged));
        return new AcknowledgmentResult(AcknowledgmentOutcome.ACKNOWLEDGED, acknowledged);
    }

    private void requireActiveUser(long actorId) {
        User actor = users.findById(actorId).orElse(null);
        if (actor == null || !actor.enabled() || (actor.role() != Role.OPERATOR && actor.role() != Role.ADMIN)) {
            throw new OperationNotPermittedException("Only an active operator or administrator can acknowledge alerts.");
        }
    }

    /** Returns the stripped note, or {@code null} when blank. */
    private static String validateNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String stripped = note.strip();
        if (stripped.length() > MAX_NOTE_LENGTH) {
            throw new InvalidInputException("note", "Note must be at most " + MAX_NOTE_LENGTH + " characters.");
        }
        return stripped;
    }

    /**
     * Audit details: what was acknowledged and in which state. The note text stays on the alert row, where it
     * cannot change after acknowledgment; only its length is recorded, so the bounded details are never truncated.
     */
    private static String details(Alert alert) {
        StringBuilder details = new StringBuilder()
                .append("device=").append(alert.deviceId())
                .append(", session=").append(alert.sessionId())
                .append(", kind=").append(alert.kind())
                .append(", severity=").append(alert.severity())
                .append(", source=").append(alert.source());
        if (alert.kind() == AlertKind.THRESHOLD) {
            details.append(", metric=").append(alert.metric()).append(' ').append(alert.direction());
        }
        return details.append(", alertActive=").append(alert.active())
                .append(", noteLength=").append(alert.note() == null ? 0 : alert.note().length())
                .toString();
    }
}
