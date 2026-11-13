package com.icu.smart_cold_storage_monitor.simulator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recorded samples per device, each list ordered by sequence number. Devices are in id order. */
record Scenario(Map<String, List<SamplePoint>> samplesByDevice) {

    Scenario {
        if (samplesByDevice.isEmpty()) {
            throw new IllegalArgumentException("A scenario needs at least one device.");
        }
        Map<String, List<SamplePoint>> copy = new LinkedHashMap<>();
        samplesByDevice.forEach((device, samples) -> {
            if (samples.isEmpty()) {
                throw new IllegalArgumentException("Device " + device + " has no samples.");
            }
            copy.put(device, List.copyOf(samples));
        });
        samplesByDevice = Collections.unmodifiableMap(copy);
    }

    Set<String> devices() {
        return samplesByDevice.keySet();
    }

    List<SamplePoint> samples(String device) {
        List<SamplePoint> samples = samplesByDevice.get(device);
        if (samples == null) {
            throw new IllegalArgumentException("Unknown device " + device + ".");
        }
        return samples;
    }
}
