package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.repository.ReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Limits how much reading history is kept. Devices, storage sessions, threshold versions, and audit events are never
 * touched. When tables that reference {@code readings} are added (predictions), delete their rows here first.
 */
@Service
public class ReadingRetentionService {

    private final ReadingRepository readings;

    public ReadingRetentionService(ReadingRepository readings) {
        this.readings = readings;
    }

    /** Deletes every stored reading and returns how many were removed. */
    @Transactional
    public int clearAllReadings() {
        return readings.deleteAll();
    }
}
