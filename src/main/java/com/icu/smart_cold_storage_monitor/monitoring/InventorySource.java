package com.icu.smart_cold_storage_monitor.monitoring;
import java.time.Instant;
import java.util.List;
import com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.Lot;
public interface InventorySource {
    List<Lot> loadStock(Instant asOf);
}
