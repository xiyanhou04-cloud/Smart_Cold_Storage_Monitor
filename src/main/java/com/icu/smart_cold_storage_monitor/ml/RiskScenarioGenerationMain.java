package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Mode;
import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Reading;
import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Scenario;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;

/** Generates and audits additional synthetic data without training any classifier. */
public final class RiskScenarioGenerationMain {

    private RiskScenarioGenerationMain() {}

    record Config(String generatorVersion, long randomSeed, Instant firstSessionStart, int cadenceSeconds,
                  int mediumAfterSeconds, int highAfterSeconds) {
        Config {
            if (!RiskScenarioGenerator.VERSION.equals(generatorVersion) || firstSessionStart == null
                    || cadenceSeconds < 1 || cadenceSeconds > 30) {
                throw new IllegalArgumentException("Expected a supported generator, start time and cadence of 1–30 seconds.");
            }
            new RiskRules(mediumAfterSeconds, highAfterSeconds);
        }
    }

    record Count(long records, long scenarios) {}
    record ScenarioSummary(Scenario scenario, Instant firstTimestamp, Instant lastTimestamp, int records,
                           int missingTicks, int gapIntervals, int aboveUpperEpisodes,
                           long observedAboveUpperSeconds, Map<RiskLevel, Long> labels) {}
    record Manifest(Config config, String dataSha256, int scenarioCount, long recordCount, int missingTicks,
                    int gapIntervals, int duplicateTemperatureTraces, Map<String, Count> regions,
                    Map<String, Count> products, Map<Mode, Count> scenarioTypes, Map<RiskLevel, Count> labels,
                    Set<String> highProducts, Set<String> highRegions, Set<Mode> highScenarioTypes,
                    List<ScenarioSummary> scenarios) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: RiskScenarioGenerationMain [config.json] [output-directory]");
        }
        Path config = Path.of(args.length > 0 ? args[0] : "testData/ML_Scenarios/generator-config.json");
        Path output = Path.of(args.length > 1 ? args[1] : "target/ml-scenarios");
        Manifest manifest = run(config, output);
        System.out.printf("Generated %,d records in %d scenarios%n", manifest.recordCount(), manifest.scenarioCount());
        System.out.println("Labels: " + manifest.labels());
        System.out.println("High products: " + manifest.highProducts());
        System.out.println("High modes: " + manifest.highScenarioTypes());
        System.out.println("Data SHA-256: " + manifest.dataSha256());
    }

    static Manifest run(Path configPath, Path output) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Config config = mapper.readValue(configPath.toFile(), Config.class);
        var limits = RiskTrainingData.locationThresholds().locations();
        var rules = new RiskRules(config.mediumAfterSeconds(), config.highAfterSeconds());
        Random seeds = new Random(config.randomSeed());
        Set<Long> usedSeeds = new HashSet<>();
        Set<List<Double>> temperatureTraces = new HashSet<>();
        List<ScenarioSummary> summaries = new ArrayList<>();
        var regions = new TreeMap<String, Count>();
        var products = new TreeMap<String, Count>();
        var modes = new EnumMap<Mode, Count>(Mode.class);
        var labels = new EnumMap<RiskLevel, Count>(RiskLevel.class);
        var highProducts = new TreeSet<String>();
        var highRegions = new TreeSet<String>();
        var highModes = EnumSet.noneOf(Mode.class);
        for (RiskLevel level : RiskLevel.values()) {
            labels.put(level, new Count(0, 0));
        }

        Files.createDirectories(output);
        Path dataset = output.resolve("generated-readings.jsonl");
        var digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        int gaps = 0;
        int missing = 0;
        try (var writer = new BufferedWriter(new OutputStreamWriter(
                new DigestOutputStream(Files.newOutputStream(dataset), digest), StandardCharsets.UTF_8))) {
            for (String region : RiskTrainingData.REGION_PRODUCTS.keySet().stream().sorted().toList()) {
                int number = 0;
                for (Mode mode : RiskScenarioGenerator.SCENARIO_PLAN) {
                    long seed = seeds.nextLong();
                    if (!usedSeeds.add(seed)) {
                        throw new IllegalStateException("Duplicate scenario seed.");
                    }
                    String id = RiskScenarioGenerator.VERSION + "-" + Long.toUnsignedString(config.randomSeed(), 16)
                            + "-" + region + "-" + ++number;
                    Instant start = config.firstSessionStart().plusSeconds(86400L * summaries.size());
                    Scenario scenario = RiskScenarioGenerator.plan(id, seed, region, start, mode,
                            limits.get(region), config.cadenceSeconds(), rules);
                    List<Reading> readings = RiskScenarioGenerator.generate(scenario);
                    var stats = RiskScenarioQuality.validate(scenario, readings);
                    if (!temperatureTraces.add(stats.temperatureTrace())) {
                        throw new IllegalStateException("Identical temperature trajectory in " + id);
                    }
                    var scenarioLabels = new EnumMap<RiskLevel, Long>(RiskLevel.class);
                    for (RiskLevel level : RiskLevel.values()) {
                        scenarioLabels.put(level, 0L);
                    }
                    for (Reading reading : readings) {
                        writer.write(mapper.writeValueAsString(reading));
                        writer.write('\n');
                        scenarioLabels.merge(reading.riskLevel(), 1L, Long::sum);
                    }
                    scenarioLabels.forEach((level, total) -> {
                        if (total > 0) {
                            add(labels, level, total);
                        }
                    });
                    if (scenarioLabels.get(RiskLevel.HIGH) > 0) {
                        highProducts.add(scenario.productType());
                        highRegions.add(region);
                        highModes.add(mode);
                    }
                    add(regions, region, stats.records());
                    add(products, scenario.productType(), stats.records());
                    add(modes, mode, stats.records());
                    count += stats.records();
                    gaps += stats.gapIntervals();
                    missing += stats.missingTicks();
                    summaries.add(new ScenarioSummary(scenario, readings.getFirst().timestamp(),
                            readings.getLast().timestamp(), stats.records(), stats.missingTicks(), stats.gapIntervals(),
                            stats.aboveUpperEpisodes(), stats.observedAboveUpperSeconds(), scenarioLabels));
                }
            }
        }
        var result = new Manifest(config, HexFormat.of().formatHex(digest.digest()), summaries.size(), count,
                missing, gaps, 0, regions, products, modes, labels, highProducts, highRegions, highModes, summaries);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Files.writeString(output.resolve("scenario-manifest.json"), json.replace("\r\n", "\n") + "\n");
        return result;
    }

    private static <K> void add(Map<K, Count> totals, K key, long size) {
        totals.merge(key, new Count(size, 1),
                (a, b) -> new Count(a.records() + b.records(), a.scenarios() + b.scenarios()));
    }
}
