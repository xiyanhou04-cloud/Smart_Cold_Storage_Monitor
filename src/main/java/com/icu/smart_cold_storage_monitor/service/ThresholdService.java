package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.config.ThresholdProperties;
import com.icu.smart_cold_storage_monitor.model.Device;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.StorageSession;
import com.icu.smart_cold_storage_monitor.model.ThresholdChangeRequest;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.model.ThresholdRequestStatus;
import com.icu.smart_cold_storage_monitor.model.ThresholdSetting;
import com.icu.smart_cold_storage_monitor.model.User;
import com.icu.smart_cold_storage_monitor.repository.DeviceRepository;
import com.icu.smart_cold_storage_monitor.repository.StorageSessionRepository;
import com.icu.smart_cold_storage_monitor.repository.ThresholdChangeRequestRepository;
import com.icu.smart_cold_storage_monitor.repository.ThresholdSettingRepository;
import com.icu.smart_cold_storage_monitor.repository.ThresholdSettingRepository.SessionWithoutThreshold;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.service.exception.ConflictException;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import com.icu.smart_cold_storage_monitor.service.exception.NotFoundException;
import com.icu.smart_cold_storage_monitor.service.exception.OperationNotPermittedException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Threshold limits per device (FR-04): temperature and humidity lower and upper limits, versioned per storage session.
 *
 * <ul>
 *   <li>Every new storage session starts with the default for its device's location ({@link ThresholdProperties});
 *       limits set for an earlier session are not carried over.</li>
 *   <li>An administrator's change applies at once as a new version. An operator's change becomes a request that an
 *       administrator approves (applied as a new version) or rejects; the operator can cancel it.</li>
 *   <li>A device has at most one pending request. A new request from the same operator replaces their pending one;
 *       a request from someone else is refused while one is pending. An administrator's direct change, or a new
 *       storage session, supersedes the pending request.</li>
 *   <li>Approval is refused when the device's current version is no longer the one the request was based on.</li>
 *   <li>Versions are never updated or deleted, so past readings keep the version they were checked against.</li>
 * </ul>
 *
 * Each change and its audit events are written in one transaction, so an audit failure rejects the change. Reads are
 * not transactional (a transaction would take SQLite's write lock).
 */
@Service
public class ThresholdService {

    static final int MAX_NOTE_LENGTH = 500;

    private final ThresholdSettingRepository thresholds;
    private final ThresholdChangeRequestRepository requests;
    private final DeviceRepository devices;
    private final StorageSessionRepository sessions;
    private final UserRepository users;
    private final AuditService audit;
    private final ThresholdProperties defaults;
    private final Clock clock;

    public ThresholdService(ThresholdSettingRepository thresholds, ThresholdChangeRequestRepository requests,
                            DeviceRepository devices, StorageSessionRepository sessions, UserRepository users,
                            AuditService audit, ThresholdProperties defaults, Clock clock) {
        this.thresholds = thresholds;
        this.requests = requests;
        this.devices = devices;
        this.sessions = sessions;
        this.users = users;
        this.audit = audit;
        this.defaults = defaults;
        this.clock = clock;
    }

    /**
     * A device with its active session, that session's current limits, and the pending change request.
     *
     * @param session        the active storage session, or {@code null} when the device has none
     * @param current        the session's current version, or {@code null} when there is no active session
     * @param pendingRequest the device's pending request, or {@code null}
     */
    public record DeviceThreshold(Device device, StorageSession session, ThresholdSetting current,
                                  ThresholdChangeRequest pendingRequest) {
    }

    /** What a change submission did. */
    public enum SubmissionOutcome {
        /** An administrator's change is now the current version. */
        APPLIED,
        /** The limits equal the current version; nothing was written. */
        UNCHANGED,
        /** An operator's change waits for administrator approval. */
        PENDING_APPROVAL
    }

    /**
     * @param threshold the device after the submission
     * @param request   the pending request for {@link SubmissionOutcome#PENDING_APPROVAL}, otherwise {@code null}
     */
    public record SubmissionResult(SubmissionOutcome outcome, DeviceThreshold threshold,
                                   ThresholdChangeRequest request) {
    }

    /** @param threshold the device after the review */
    public record ReviewResult(ThresholdChangeRequest request, DeviceThreshold threshold) {
    }

    // ---- Reading ------------------------------------------------------------------------------------------------

    /** Every registered device in id order with its current limits. */
    public List<DeviceThreshold> listDeviceThresholds() {
        Map<String, StorageSession> activeByDevice = sessions.findAllActive().stream()
                .collect(Collectors.toMap(StorageSession::deviceId, Function.identity()));
        Map<Long, ThresholdSetting> currentBySession = thresholds.findCurrentForActiveSessions().stream()
                .collect(Collectors.toMap(ThresholdSetting::sessionId, Function.identity()));
        Map<String, ThresholdChangeRequest> pendingByDevice = requests.findAllPending().stream()
                .collect(Collectors.toMap(ThresholdChangeRequest::deviceId, Function.identity()));
        return devices.findAll().stream().map(device -> {
            StorageSession session = activeByDevice.get(device.id());
            ThresholdSetting current = session == null ? null : currentBySession.get(session.id());
            return new DeviceThreshold(device, session, current, pendingByDevice.get(device.id()));
        }).toList();
    }

    /** @throws NotFoundException if the device is not registered */
    public DeviceThreshold getDeviceThreshold(String deviceId) {
        Device device = findDevice(deviceId);
        StorageSession session = sessions.findActiveByDevice(deviceId).orElse(null);
        ThresholdSetting current = session == null ? null
                : thresholds.findCurrentBySession(session.id()).orElse(null);
        return new DeviceThreshold(device, session, current, requests.findPendingByDevice(deviceId).orElse(null));
    }

    /**
     * Change requests, newest first. An administrator sees every request; an operator only their own.
     *
     * @param deviceId only this device, or {@code null}
     * @param statuses only these statuses, or {@code null}/empty for all
     */
    public PagedResult<ThresholdChangeRequest> searchRequests(long actorId, String deviceId,
                                                              Collection<ThresholdRequestStatus> statuses,
                                                              PageQuery page) {
        User actor = requireActiveUser(actorId);
        var filter = new ThresholdChangeRequestRepository.Filter(deviceId, statuses,
                actor.role() == Role.ADMIN ? null : actor.id());
        return new PagedResult<>(requests.search(filter, page.offset(), page.size()), page.page(), page.size(),
                requests.count(filter));
    }

    /** Id of the session's current version for a reading being stored, or empty when the session has none. */
    public Optional<Long> currentThresholdId(long sessionId) {
        return thresholds.findCurrentIdBySession(sessionId);
    }

    // ---- Defaults -----------------------------------------------------------------------------------------------

    /**
     * Gives a storage session that was just started the default limits for its device's location, and supersedes
     * the device's pending request (it was based on the previous session's limits). Joins the caller's transaction
     * so the session and its first version commit together. No separate audit event: the caller's session-start
     * event covers it.
     *
     * @return the new version
     */
    @Transactional
    public ThresholdSetting assignDefault(StorageSession session) {
        Device device = findDevice(session.deviceId());
        Instant now = now();
        long id = thresholds.insert(session.id(), defaults.defaultFor(device.locationCode()), now, null);
        requests.findPendingByDevice(device.id()).ifPresent(pending -> requests.conclude(pending.id(),
                ThresholdRequestStatus.SUPERSEDED, null, now,
                "Storage session " + session.id() + " started; limits were reset to the default.", null));
        return thresholds.findById(id).orElseThrow();
    }

    /**
     * Gives every active session that has no version (e.g. one started before thresholds existed) its location
     * default. Returns how many sessions were given one.
     */
    @Transactional
    public int assignMissingDefaults() {
        List<SessionWithoutThreshold> missing = thresholds.findActiveSessionsWithoutThreshold();
        Instant now = now();
        for (SessionWithoutThreshold session : missing) {
            thresholds.insert(session.sessionId(), defaults.defaultFor(session.locationCode()), now, null);
        }
        return missing.size();
    }

    // ---- Changes ------------------------------------------------------------------------------------------------

    /**
     * Submits new limits for a device: applied at once for an administrator, a pending request for an operator.
     * Submitting the current limits changes nothing; an operator repeating their pending request gets that request
     * back unchanged.
     *
     * @throws InvalidInputException          if the limits break a rule of {@link ThresholdLimits#findViolation()}
     * @throws NotFoundException              if the device is not registered
     * @throws ConflictException              {@code NO_ACTIVE_SESSION} if the device has no active storage session;
     *                                        {@code THRESHOLD_REQUEST_PENDING} if another user's request is pending
     * @throws OperationNotPermittedException if the actor is not an active user
     */
    @Transactional
    public SubmissionResult submit(long actorId, String deviceId, ThresholdLimits limits) {
        User actor = requireActiveUser(actorId);
        limits.findViolation().ifPresent(v -> {
            throw new InvalidInputException(v.field(), v.message());
        });
        Device device = findDevice(deviceId);
        StorageSession session = requireActiveSession(deviceId);
        ThresholdSetting current = currentOrDefault(session, device);
        if (limits.equals(current.limits())) {
            return new SubmissionResult(SubmissionOutcome.UNCHANGED, getDeviceThreshold(deviceId), null);
        }
        Optional<ThresholdChangeRequest> pending = requests.findPendingByDevice(deviceId);
        Instant now = now();

        if (actor.role() == Role.ADMIN) {
            long newId = thresholds.insert(session.id(), limits, now, actor.id());
            String superseded = "";
            if (pending.isPresent()) {
                requests.conclude(pending.get().id(), ThresholdRequestStatus.SUPERSEDED, actor.id(), now,
                        "Superseded by an administrator change (threshold version " + newId + ").", null);
                superseded = ", supersededRequest=" + pending.get().id();
            }
            audit.recordThresholdChanged(actor.id(), newId, changeDetails(device, session, current, limits)
                    + ", source=ADMIN" + superseded);
            return new SubmissionResult(SubmissionOutcome.APPLIED, getDeviceThreshold(deviceId), null);
        }

        String replaced = "";
        if (pending.isPresent()) {
            ThresholdChangeRequest existing = pending.get();
            if (existing.requestedBy() != actor.id()) {
                throw new ConflictException("THRESHOLD_REQUEST_PENDING", "Device " + deviceId
                        + " already has a pending threshold change request (" + existing.id() + ") by "
                        + existing.requestedByUsername() + "; it must be reviewed or cancelled first.");
            }
            if (existing.limits().equals(limits) && existing.baseThresholdId() == current.id()) {
                return new SubmissionResult(SubmissionOutcome.PENDING_APPROVAL, getDeviceThreshold(deviceId),
                        existing);
            }
            requests.conclude(existing.id(), ThresholdRequestStatus.SUPERSEDED, actor.id(), now,
                    "Replaced by a newer request from the same user.", null);
            replaced = ", replacesRequest=" + existing.id();
        }
        long requestId;
        try {
            requestId = requests.insertPending(deviceId, current.id(), limits, actor.id(), now);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("THRESHOLD_REQUEST_PENDING",
                    "Device " + deviceId + " already has a pending threshold change request.");
        }
        audit.recordThresholdChangeRequested(actor.id(), requestId,
                changeDetails(device, session, current, limits) + replaced);
        return new SubmissionResult(SubmissionOutcome.PENDING_APPROVAL, getDeviceThreshold(deviceId),
                requests.findById(requestId).orElseThrow());
    }

    /**
     * Applies a pending request's limits as the device's new current version.
     *
     * @param note optional reviewer note, at most {@value #MAX_NOTE_LENGTH} characters
     * @throws OperationNotPermittedException if the actor is not an active administrator
     * @throws NotFoundException              if the request does not exist
     * @throws ConflictException              {@code THRESHOLD_REQUEST_NOT_PENDING}, {@code NO_ACTIVE_SESSION}, or
     *                                        {@code THRESHOLD_REQUEST_STALE} when the current version changed since
     *                                        the request was made
     */
    @Transactional
    public ReviewResult approve(long actorId, long requestId, String note) {
        User admin = requireActiveAdmin(actorId);
        String cleanNote = validateNote(note);
        ThresholdChangeRequest request = requirePending(requestId);
        Device device = findDevice(request.deviceId());
        StorageSession session = requireActiveSession(device.id());
        ThresholdSetting current = currentOrDefault(session, device);
        if (current.id() != request.baseThresholdId()) {
            throw new ConflictException("THRESHOLD_REQUEST_STALE", "The limits of device " + device.id()
                    + " changed after request " + requestId + " was made; reject it and submit a new one.");
        }
        Instant now = now();
        long newId = thresholds.insert(session.id(), request.limits(), now, admin.id());
        concludeOrConflict(requestId, ThresholdRequestStatus.APPROVED, admin.id(), now, cleanNote, newId);
        audit.recordThresholdChangeApproved(admin.id(), requestId, "device=" + device.id()
                + ", requestedBy=" + request.requestedByUsername() + ", thresholdId=" + newId);
        audit.recordThresholdChanged(admin.id(), newId, changeDetails(device, session, current, request.limits())
                + ", source=REQUEST, request=" + requestId + ", requestedBy=" + request.requestedByUsername());
        return new ReviewResult(requests.findById(requestId).orElseThrow(), getDeviceThreshold(device.id()));
    }

    /**
     * Declines a pending request; the device's limits stay as they are.
     *
     * @throws OperationNotPermittedException if the actor is not an active administrator
     * @throws NotFoundException              if the request does not exist
     * @throws ConflictException              {@code THRESHOLD_REQUEST_NOT_PENDING}
     */
    @Transactional
    public ReviewResult reject(long actorId, long requestId, String note) {
        User admin = requireActiveAdmin(actorId);
        String cleanNote = validateNote(note);
        ThresholdChangeRequest request = requirePending(requestId);
        concludeOrConflict(requestId, ThresholdRequestStatus.REJECTED, admin.id(), now(), cleanNote, null);
        audit.recordThresholdChangeRejected(admin.id(), requestId, "device=" + request.deviceId()
                + ", requestedBy=" + request.requestedByUsername() + ", proposed=" + request.limits().describe());
        return new ReviewResult(requests.findById(requestId).orElseThrow(), getDeviceThreshold(request.deviceId()));
    }

    /**
     * Withdraws the actor's own pending request.
     *
     * @throws OperationNotPermittedException if the actor did not make the request
     * @throws NotFoundException              if the request does not exist
     * @throws ConflictException              {@code THRESHOLD_REQUEST_NOT_PENDING}
     */
    @Transactional
    public ReviewResult cancel(long actorId, long requestId) {
        User actor = requireActiveUser(actorId);
        ThresholdChangeRequest request = requests.findById(requestId).orElseThrow(() -> requestNotFound(requestId));
        if (request.requestedBy() != actor.id()) {
            throw new OperationNotPermittedException("Only the user who made a request can cancel it.");
        }
        requirePending(requestId);
        concludeOrConflict(requestId, ThresholdRequestStatus.CANCELLED, actor.id(), now(), null, null);
        audit.recordThresholdChangeCancelled(actor.id(), requestId, "device=" + request.deviceId());
        return new ReviewResult(requests.findById(requestId).orElseThrow(), getDeviceThreshold(request.deviceId()));
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    /** The session's current version, giving it the location default first if it has none. */
    private ThresholdSetting currentOrDefault(StorageSession session, Device device) {
        return thresholds.findCurrentBySession(session.id()).orElseGet(() -> {
            long id = thresholds.insert(session.id(), defaults.defaultFor(device.locationCode()), now(), null);
            return thresholds.findById(id).orElseThrow();
        });
    }

    private ThresholdChangeRequest requirePending(long requestId) {
        ThresholdChangeRequest request = requests.findById(requestId).orElseThrow(() -> requestNotFound(requestId));
        if (!request.pending()) {
            throw notPending(request);
        }
        return request;
    }

    private void concludeOrConflict(long requestId, ThresholdRequestStatus status, Long reviewedBy, Instant at,
                                    String note, Long appliedThreshold) {
        if (!requests.conclude(requestId, status, reviewedBy, at, note, appliedThreshold)) {
            throw notPending(requests.findById(requestId).orElseThrow());
        }
    }

    private static ConflictException notPending(ThresholdChangeRequest request) {
        return new ConflictException("THRESHOLD_REQUEST_NOT_PENDING",
                "Threshold change request " + request.id() + " is already " + request.status() + ".");
    }

    private static NotFoundException requestNotFound(long requestId) {
        return new NotFoundException("Threshold change request " + requestId + " does not exist.");
    }

    private Device findDevice(String deviceId) {
        return devices.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("Device " + deviceId + " is not registered."));
    }

    private StorageSession requireActiveSession(String deviceId) {
        return sessions.findActiveByDevice(deviceId).orElseThrow(() -> new ConflictException("NO_ACTIVE_SESSION",
                "Device " + deviceId + " has no active storage session; limits belong to a session."));
    }

    private User requireActiveUser(long actorId) {
        User actor = users.findById(actorId).orElse(null);
        if (actor == null || !actor.enabled()) {
            throw new OperationNotPermittedException("Only an active user can change thresholds.");
        }
        return actor;
    }

    private User requireActiveAdmin(long actorId) {
        User actor = requireActiveUser(actorId);
        if (actor.role() != Role.ADMIN) {
            throw new OperationNotPermittedException("Only an administrator can review threshold change requests.");
        }
        return actor;
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

    private static String changeDetails(Device device, StorageSession session, ThresholdSetting current,
                                        ThresholdLimits proposed) {
        return "device=" + device.id() + ", session=" + session.id() + ", previousVersion=" + current.id()
                + ", old=" + current.limits().describe() + ", new=" + proposed.describe();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
