package com.icu.smart_cold_storage_monitor.simulator;

import com.icu.smart_cold_storage_monitor.monitoring.Sht45Codec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the timestamped scenario JSONL (one sample per line) and checks it before any reading is
 * sent: every line must parse, its values must match its SHT45 frame, and each device's samples
 * must form a gap-free sequence at the recorded five-second step.
 */
final class ScenarioLoader {
    static final long SAMPLE_STEP_SECONDS = 5;
    private static final double FRAME_TOLERANCE = 0.01;

    private final ObjectMapper mapper;

    ScenarioLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param devices devices to keep; an empty set keeps every device in the file
     */
    Scenario load(Path path, Set<String> devices) throws IOException {
        Map<String, List<SamplePoint>> byDevice = new TreeMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            SamplePoint sample;
            try {
                sample = parse(mapper.readTree(line));
            } catch (JacksonException | IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                    "%s line %d: %s".formatted(path, i + 1, ex.getMessage()), ex);
            }
            if (devices.isEmpty() || devices.contains(sample.deviceId())) {
                byDevice.computeIfAbsent(sample.deviceId(), d -> new ArrayList<>()).add(sample);
            }
        }
        for (String device : devices) {
            if (!byDevice.containsKey(device)) {
                throw new IllegalArgumentException("Device " + device + " is not in " + path + ".");
            }
        }
        if (byDevice.isEmpty()) {
            throw new IllegalArgumentException(path + " contains no samples.");
        }
        Map<String, List<SamplePoint>> ordered = new LinkedHashMap<>();
        byDevice.forEach((device, samples) -> {
            samples.sort(Comparator.comparingLong(SamplePoint::sequence));
            checkContinuous(device, samples);
            ordered.put(device, samples);
        });
        return new Scenario(ordered);
    }

    private static SamplePoint parse(JsonNode node) {
        String device = text(node, "deviceId");
        long sequence = nonNegativeLong(node, "sequence_number");
        long elapsed = nonNegativeLong(node, "elapsed_seconds");
        double temperature = finite(node, "temperatureC");
        double humidity = finite(node, "humidityPct");
        double[] decoded = Sht45Codec.decode(text(node, "frame_hex"));
        if (Math.abs(temperature - decoded[0]) > FRAME_TOLERANCE
                || Math.abs(humidity - decoded[1]) > FRAME_TOLERANCE) {
            throw new IllegalArgumentException("temperatureC/humidityPct do not match frame_hex.");
        }
        JsonNode location = node.get("location_code");
        String locationCode = location != null && location.isString() ? location.asString() : null;
        return new SamplePoint(device, locationCode, sequence, elapsed, temperature, humidity);
    }

    private static void checkContinuous(String device, List<SamplePoint> samples) {
        for (int i = 0; i < samples.size(); i++) {
            SamplePoint sample = samples.get(i);
            if (sample.sequence() != i) {
                throw new IllegalArgumentException(
                    "Device %s: expected sequence_number %d, found %d.".formatted(device, i, sample.sequence()));
            }
            if (i > 0 && sample.elapsedSeconds() - samples.get(i - 1).elapsedSeconds() != SAMPLE_STEP_SECONDS) {
                throw new IllegalArgumentException(
                    "Device %s: sequence_number %d is not %d s after the previous sample."
                        .formatted(device, i, SAMPLE_STEP_SECONDS));
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Missing " + field + ".");
        }
        return value.asString();
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException("Expected a nonnegative integer " + field + ".");
        }
        return value.asLong();
    }

    private static double finite(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IllegalArgumentException("Expected a finite number " + field + ".");
        }
        return value.asDouble();
    }
}
