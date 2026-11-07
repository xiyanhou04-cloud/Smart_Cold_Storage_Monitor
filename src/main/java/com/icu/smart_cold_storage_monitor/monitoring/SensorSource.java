package com.icu.smart_cold_storage_monitor.monitoring;
import com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.SensorSnapshot;
public interface SensorSource {
    SensorSnapshot load();
}
