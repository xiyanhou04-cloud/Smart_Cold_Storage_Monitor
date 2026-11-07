package com.icu.smart_cold_storage_monitor.monitoring;

import java.time.Instant;
import java.util.List;

public final class MonitoringModels {
    private MonitoringModels() {}
    public enum Status { OK, WARNING, ACTION_REQUIRED, UNKNOWN }
    public record Lot(long id, String code, String sku, String product, String unit, long quantity,
                      String location, String qualityStatus, Instant receivedAt, Instant useBy,
                      Double tempMin, Double tempMax, Double rhMin, Double rhMax,
                      String conditionNote, String sourceUrl) {}
    public record Reading(String deviceId, String bootId, long sequence, Instant measuredAt,
                          Instant receivedAt, double temperatureC, double humidityPct,
                          boolean synthetic, String clockStatus, String timeSource) {}
    public record SensorSnapshot(List<Reading> readings, int rejectedRecords, List<String> issues) {}
    public record Finding(String code, Status severity, String message) {}
    public record Assessment(Lot lot, String deviceId, Reading reading, Status status,
                             Status environmentStatus, boolean humiditySpecified, boolean temperatureSpecified,
                             List<Finding> findings) {}
    public record Report(Instant evaluatedAt, Instant generatedAt, String mode, String ruleVersion,
                         String inventorySource, String sensorSource, List<String> sourceIssues,
                         int rejectedSensorRecords, List<Assessment> lots) {}
}
