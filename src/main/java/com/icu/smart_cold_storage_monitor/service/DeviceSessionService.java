package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.StorageSession;
import com.icu.smart_cold_storage_monitor.model.ThresholdSetting;
import com.icu.smart_cold_storage_monitor.repository.DeviceRepository;
import com.icu.smart_cold_storage_monitor.repository.StorageSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions devices and their storage sessions. Used at startup by the built-in simulator; the administrator
 * storage-session API ({@code POST /api/storage-sessions}) is not built yet.
 */
@Service
public class DeviceSessionService {

    private final DeviceRepository devices;
    private final StorageSessionRepository sessions;
    private final ThresholdService thresholds;
    private final AuditService audit;
    private final Clock clock;

    public DeviceSessionService(DeviceRepository devices, StorageSessionRepository sessions,
                                ThresholdService thresholds, AuditService audit, Clock clock) {
        this.devices = devices;
        this.sessions = sessions;
        this.thresholds = thresholds;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Registers the device if it is new (an existing device keeps its name, enabled flag, and location; only a
     * missing location is filled in) and returns its active storage session, starting one now if there is none.
     * A started session gets the default threshold limits of the device's location and is audited in the same
     * transaction with no actor, because the system started it.
     * {@code locationCode} may be {@code null} when the zone is unknown.
     */
    @Transactional
    public StorageSession ensureDeviceWithActiveSession(String deviceId, String deviceName, String locationCode,
                                                        String productType) {
        devices.insertIfAbsent(deviceId, deviceName, locationCode);
        return sessions.findActiveByDevice(deviceId).orElseGet(() -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            long id = sessions.insert(deviceId, productType, now);
            StorageSession session = new StorageSession(id, deviceId, productType, now, null);
            ThresholdSetting limits = thresholds.assignDefault(session);
            audit.recordStorageSessionStarted(null, id,
                    "device=" + deviceId + ", productType=" + productType + ", startedBy=SYSTEM, thresholdId="
                            + limits.id() + ", defaultLimits=" + limits.limits().describe());
            return session;
        });
    }
}
