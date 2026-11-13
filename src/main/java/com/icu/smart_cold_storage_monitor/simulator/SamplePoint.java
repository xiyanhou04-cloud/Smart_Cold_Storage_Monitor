package com.icu.smart_cold_storage_monitor.simulator;

/**
 * One recorded sensor sample from the scenario file. Only the values and the position within the
 * scenario are kept; the recorded {@code measured_at} is discarded because the simulator stamps
 * each reading with its actual send time.
 */
record SamplePoint(String deviceId, String locationCode, long sequence, long elapsedSeconds,
                   double temperatureC, double humidityPct) {
}
