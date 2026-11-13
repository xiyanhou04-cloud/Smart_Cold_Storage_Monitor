package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.ThresholdSetting;
import com.icu.smart_cold_storage_monitor.service.ThresholdService.DeviceThreshold;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One row of the threshold settings table: a device and the limits of its active storage session. The session and
 * limit fields are null when the device has no active session.
 */
public record DeviceThresholdResponse(
        String deviceId,
        String deviceName,
        String locationCode,
        boolean enabled,
        Long sessionId,
        @Schema(description = "Id of the current threshold version")
        Long thresholdId,
        Double temperatureLowerC,
        Double temperatureUpperC,
        Double humidityLowerPct,
        Double humidityUpperPct,
        Instant effectiveFrom,
        @Schema(description = "DEFAULT: the location default the session started with; CUSTOM: set by a user")
        Source source,
        @Schema(description = "Username of whoever made the current version current; null for DEFAULT")
        String changedBy,
        @Schema(description = "The device's pending change request, or null")
        ThresholdRequestResponse pendingRequest) {

    public enum Source {
        DEFAULT,
        CUSTOM
    }

    public static DeviceThresholdResponse from(DeviceThreshold threshold) {
        ThresholdSetting current = threshold.current();
        return new DeviceThresholdResponse(
                threshold.device().id(),
                threshold.device().name(),
                threshold.device().locationCode(),
                threshold.device().enabled(),
                threshold.session() == null ? null : threshold.session().id(),
                current == null ? null : current.id(),
                current == null ? null : current.limits().temperatureLowerC(),
                current == null ? null : current.limits().temperatureUpperC(),
                current == null ? null : current.limits().humidityLowerPct(),
                current == null ? null : current.limits().humidityUpperPct(),
                current == null ? null : current.effectiveFrom(),
                current == null ? null : current.systemDefault() ? Source.DEFAULT : Source.CUSTOM,
                current == null ? null : current.changedByUsername(),
                threshold.pendingRequest() == null ? null : ThresholdRequestResponse.from(threshold.pendingRequest()));
    }
}
