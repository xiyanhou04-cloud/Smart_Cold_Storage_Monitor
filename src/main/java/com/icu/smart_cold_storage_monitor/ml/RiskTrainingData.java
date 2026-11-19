package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.config.ThresholdProperties;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.monitoring.Sht45Codec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Adapter for the checked-in seven-zone demo fixture. */
final class RiskTrainingData {

    static final Set<String> TEST_REGIONS = Set.of("VEG-COLD");
    static final Map<String, String> REGION_PRODUCTS = Map.of(
            "RAW-MEAT", "RAW_MEAT", "FROZEN", "RAW_MEAT",
            "VEG-COLD", "PRODUCE", "VEG-COOL", "PRODUCE",
            "FRUIT-COLD-BERRY", "FRUIT", "FRUIT-TROPICAL", "FRUIT", "EGGS", "EGGS");

    record Sample(String session, String region, Instant measuredAt, RiskFeatures.Snapshot features,
                  ThresholdLimits limits, RiskLevel label) {}

    private record Trace(String region, RiskFeatures features) {}

    static ThresholdProperties locationThresholds() throws java.io.IOException {
        var yaml = new YamlPropertySourceLoader().load("risk-training", new ClassPathResource("application.yaml"));
        return new Binder(ConfigurationPropertySources.from(yaml))
                .bind("app.thresholds", ThresholdProperties.class).get();
    }

    static List<Sample> read(Path path, RiskRules rules) throws Exception {
        var thresholds = locationThresholds();
        var mapper = new ObjectMapper();
        Map<String, Trace> sessions = new TreeMap<>();
        List<Sample> samples = new ArrayList<>();

        try (var reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = mapper.readTree(line);
                String region = field(row, "location_code").asString();
                String product = REGION_PRODUCTS.get(region);
                ThresholdLimits limits = thresholds.locations().get(region);
                if (product == null || limits == null) {
                    throw new IllegalArgumentException("No product mapping or threshold for " + region);
                }
                String session = field(row, "deviceId").asString() + "/" + field(row, "device_session_id").asString();
                Instant at = Instant.parse(field(row, "measured_at").asString());
                if (!sessions.containsKey(session) && field(row, "elapsed_seconds").asLong() != 0) {
                    throw new IllegalArgumentException("Expected a complete source trace for " + session);
                }
                Trace trace = sessions.computeIfAbsent(session, key -> new Trace(region, new RiskFeatures(at, product)));
                if (!trace.region().equals(region)) {
                    throw new IllegalArgumentException("Location changed in " + session);
                }
                double temperature = field(row, "temperatureC").asDouble();
                double humidity = field(row, "humidityPct").asDouble();
                double[] decoded = Sht45Codec.decode(field(row, "frame_hex").asString());
                if (Math.abs(temperature - decoded[0]) > 0.01 || Math.abs(humidity - decoded[1]) > 0.01) {
                    throw new IllegalArgumentException("Invalid SHT45 measurements in " + session + " at " + at);
                }
                var snapshot = trace.features().accept(at, temperature, humidity, limits);
                samples.add(new Sample(session, region, at, snapshot, limits, rules.predict(snapshot, limits)));
            }
        }
        return List.copyOf(samples);
    }

    private static JsonNode field(JsonNode row, String name) {
        JsonNode value = row.get(name);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }
}
