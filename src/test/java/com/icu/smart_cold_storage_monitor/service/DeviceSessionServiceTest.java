package com.icu.smart_cold_storage_monitor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.Device;
import com.icu.smart_cold_storage_monitor.model.StorageSession;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.repository.DeviceRepository;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DeviceSessionServiceTest extends SqliteIntegrationTest {

    @Autowired
    DeviceSessionService service;

    @Autowired
    DeviceRepository devices;

    @Autowired
    AuditEventRepository auditEvents;

    @Test
    void registersTheDeviceAndStartsAnAuditedSession() {
        StorageSession session =
                service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Raw meat sensor", "ZONE-A", "RAW-MEAT");

        assertThat(session.active()).isTrue();
        assertThat(session.productType()).isEqualTo("RAW-MEAT");
        assertThat(devices.findById("ESP32-SIM-001")).hasValueSatisfying(d -> {
            assertThat(d.name()).isEqualTo("Raw meat sensor");
            assertThat(d.enabled()).isTrue();
            assertThat(d.locationCode()).isEqualTo("ZONE-A");
        });
        AuditEvent event = auditEvents.findRecent(10).getFirst();
        assertThat(event.eventType()).isEqualTo(AuditEventType.STORAGE_SESSION_STARTED);
        assertThat(event.actorId()).isNull();
        assertThat(event.targetType()).isEqualTo("STORAGE_SESSION");
        assertThat(event.targetId()).isEqualTo(String.valueOf(session.id()));
    }

    @Test
    void reusesTheActiveSessionWithoutAnotherAuditEvent() {
        long first = service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Raw meat sensor", "RAW-MEAT", "RAW-MEAT")
                .id();

        long second = service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Renamed", "OTHER", "OTHER").id();

        assertThat(second).isEqualTo(first);
        Device device = devices.findById("ESP32-SIM-001").orElseThrow();
        assertThat(device.name()).isEqualTo("Raw meat sensor");
        assertThat(device.locationCode()).isEqualTo("RAW-MEAT");
        assertThat(auditEvents.findRecent(10)).hasSize(1);
    }

    @Test
    void anUnknownLocationIsStoredAsNullAndFilledInLater() {
        service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Raw meat sensor", null, "UNSPECIFIED");
        assertThat(devices.findById("ESP32-SIM-001").orElseThrow().locationCode()).isNull();

        service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Renamed", "RAW-MEAT", "RAW-MEAT");

        Device device = devices.findById("ESP32-SIM-001").orElseThrow();
        assertThat(device.locationCode()).isEqualTo("RAW-MEAT");
        assertThat(device.name()).isEqualTo("Raw meat sensor");
    }

    @Test
    void aKnownLocationIsNotClearedByAnUnknownOne() {
        service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Raw meat sensor", "RAW-MEAT", "RAW-MEAT");

        service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Raw meat sensor", null, "UNSPECIFIED");

        assertThat(devices.findById("ESP32-SIM-001").orElseThrow().locationCode()).isEqualTo("RAW-MEAT");
    }

    @Test
    void startsANewSessionAfterTheActiveOneEnded() {
        long first = service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Raw meat sensor", "RAW-MEAT", "RAW-MEAT")
                .id();
        jdbc.sql("UPDATE storage_sessions SET ended_at = started_at WHERE id = ?").param(first).update();

        long second = service.ensureDeviceWithActiveSession("ESP32-SIM-001", "Raw meat sensor", "RAW-MEAT", "RAW-MEAT")
                .id();

        assertThat(second).isNotEqualTo(first);
    }
}
