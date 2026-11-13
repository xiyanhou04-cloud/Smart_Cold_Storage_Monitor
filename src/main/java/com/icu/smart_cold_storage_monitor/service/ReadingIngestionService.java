package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.StorageSession;
import com.icu.smart_cold_storage_monitor.repository.ReadingRepository;
import com.icu.smart_cold_storage_monitor.repository.StorageSessionRepository;
import com.icu.smart_cold_storage_monitor.service.exception.ConflictException;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import com.icu.smart_cold_storage_monitor.service.exception.NotFoundException;
import com.icu.smart_cold_storage_monitor.service.exception.OperationNotPermittedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts device readings (FR-02). Each call runs in one short {@code BEGIN IMMEDIATE} transaction, so readings
 * are processed one at a time and the duplicate and ordering checks see a stable state. A rejected reading leaves
 * no trace in the monitoring data.
 *
 * <p>Rules, in order: the body's device id must be the authenticated device (403); the timestamp may be at most
 * {@link #MAX_FUTURE_SKEW} ahead of server time (400); a reading with the same device and timestamp is an identical
 * retry (200, existing record) or a conflict (409 {@code READING_CONFLICT}); the session must exist for this
 * device (404), be active (409 {@code SESSION_NOT_ACTIVE}) and have started by the timestamp (400); the timestamp
 * must be later than the device's latest stored reading (409 {@code OUT_OF_ORDER}). Timestamps are stored at
 * millisecond precision. Threshold versions and alert transitions join this transaction with FR-04.
 */
@Service
public class ReadingIngestionService {

    public static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(60);

    private final ReadingRepository readings;
    private final StorageSessionRepository sessions;
    private final Clock clock;

    public ReadingIngestionService(ReadingRepository readings, StorageSessionRepository sessions, Clock clock) {
        this.readings = readings;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** A validated request body: values are finite and within the plausible sensor range. */
    public record ReadingSubmission(String deviceId, long sessionId, Instant timestamp, double temperatureC,
                                    double humidityPct) {
    }

    /** @param created false when the submission was an identical retry of a stored reading */
    public record IngestionResult(Reading reading, boolean created) {
    }

    @Transactional
    public IngestionResult ingest(String authenticatedDeviceId, ReadingSubmission submission) {
        if (!submission.deviceId().equals(authenticatedDeviceId)) {
            throw new OperationNotPermittedException("deviceId does not match the device credential.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant measuredAt = submission.timestamp().truncatedTo(ChronoUnit.MILLIS);
        if (measuredAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            throw new InvalidInputException("timestamp",
                    "Timestamp is more than " + MAX_FUTURE_SKEW.toSeconds() + " seconds ahead of server time.");
        }

        Optional<Reading> existing = readings.findByDeviceAndMeasuredAt(submission.deviceId(), measuredAt);
        if (existing.isPresent()) {
            if (sameValues(existing.get(), submission)) {
                return new IngestionResult(existing.get(), false);
            }
            throw new ConflictException("READING_CONFLICT",
                    "A reading for this device and timestamp already exists with different values.");
        }

        StorageSession session = sessions.findById(submission.sessionId())
                .filter(s -> s.deviceId().equals(submission.deviceId()))
                .orElseThrow(() -> new NotFoundException("Storage session " + submission.sessionId()
                        + " does not exist for device " + submission.deviceId() + "."));
        if (!session.active()) {
            throw new ConflictException("SESSION_NOT_ACTIVE",
                    "Storage session " + session.id() + " has ended; readings need the active session.");
        }
        if (measuredAt.isBefore(session.startedAt())) {
            throw new InvalidInputException("timestamp", "Timestamp is before the storage session started ("
                    + session.startedAt() + ").");
        }
        Optional<Instant> latest = readings.findLatestMeasuredAt(submission.deviceId());
        if (latest.isPresent() && !measuredAt.isAfter(latest.get())) {
            throw new ConflictException("OUT_OF_ORDER",
                    "Readings must be newer than the device's latest stored reading (" + latest.get() + ").");
        }

        Reading stored = readings.insert(submission.deviceId(), session.id(), measuredAt, now,
                submission.temperatureC(), submission.humidityPct(), null);
        return new IngestionResult(stored, true);
    }

    private static boolean sameValues(Reading stored, ReadingSubmission submission) {
        return stored.sessionId() == submission.sessionId()
                && Double.compare(stored.temperatureC(), submission.temperatureC()) == 0
                && Double.compare(stored.humidityPct(), submission.humidityPct()) == 0;
    }
}
