package com.icu.smart_cold_storage_monitor.monitoring;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.monitoring")
public class MonitoringProperties {
    private Path inventoryPath = Path.of("testData/ColdStorage_Demo/cold_storage_demo.sqlite");
    private Path sensorPath = Path.of("testData/SHT45_30min/monitoring_timestamped.jsonl");
    private Duration staleAfter = Duration.ofSeconds(30);
    private Duration expiringSoon = Duration.ofHours(24);
    private long maxSensorFileBytes = 10_000_000;
    private Map<String, String> locationDevices = new LinkedHashMap<>();
    public Path getInventoryPath() { return inventoryPath; }
    public void setInventoryPath(Path value) { inventoryPath = value; }
    public Path getSensorPath() { return sensorPath; }
    public void setSensorPath(Path value) { sensorPath = value; }
    public Duration getStaleAfter() { return staleAfter; }
    public void setStaleAfter(Duration value) { staleAfter = value; }
    public Duration getExpiringSoon() { return expiringSoon; }
    public void setExpiringSoon(Duration value) { expiringSoon = value; }
    public long getMaxSensorFileBytes() { return maxSensorFileBytes; }
    public void setMaxSensorFileBytes(long value) { maxSensorFileBytes = value; }
    public Map<String, String> getLocationDevices() { return locationDevices; }
    public void setLocationDevices(Map<String, String> value) { locationDevices = value; }
    public void validate() {
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()
                || expiringSoon == null || expiringSoon.isNegative() || maxSensorFileBytes < 1 || maxSensorFileBytes >= Integer.MAX_VALUE
                || locationDevices == null || locationDevices.entrySet().stream()
                    .anyMatch(e -> e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank())) {
            throw new IllegalArgumentException("Invalid monitoring configuration.");
        }
    }
}
