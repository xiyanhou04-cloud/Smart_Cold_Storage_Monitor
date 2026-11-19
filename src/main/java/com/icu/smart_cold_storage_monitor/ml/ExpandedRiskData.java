package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerationMain.Manifest;
import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Reading;
import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Scenario;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** Loads the generated fixture and assigns whole scenes to predeclared strata. */
final class ExpandedRiskData {

    enum Partition { TRAIN, VALIDATION, TEST }

    record Scene(Scenario definition, List<Reading> rows) {
        boolean hasHigh() {
            return rows.stream().anyMatch(row -> row.riskLevel() == RiskLevel.HIGH && !row.incompleteHistory());
        }
    }

    record Dataset(Manifest manifest, List<Scene> scenes) {}

    static Dataset load(Path input, ObjectMapper mapper) throws Exception {
        Manifest manifest = mapper.readValue(input.resolve("scenario-manifest.json").toFile(), Manifest.class);
        Path data = input.resolve("generated-readings.jsonl");
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new java.security.DigestInputStream(Files.newInputStream(data), digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(manifest.dataSha256())) {
            throw new IllegalArgumentException("Generated JSONL does not match its manifest hash.");
        }
        var byId = new TreeMap<String, List<Reading>>();
        try (var reader = Files.newBufferedReader(data)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Reading row = mapper.readValue(line, Reading.class);
                byId.computeIfAbsent(row.scenarioId(), key -> new ArrayList<>()).add(row);
            }
        }
        var scenes = new ArrayList<Scene>();
        var traces = new HashSet<List<Double>>();
        var seeds = new HashSet<Long>();
        for (var summary : manifest.scenarios()) {
            Scenario definition = summary.scenario();
            List<Reading> rows = byId.remove(definition.scenarioId());
            if (rows == null || rows.size() != summary.records() || !seeds.add(definition.randomSeed())
                    || !definition.generatorVersion().equals(manifest.config().generatorVersion())
                    || !definition.labelRuleVersion().equals(RiskRules.VERSION)
                    || !definition.labelCutoffs().equals(new RiskRules(manifest.config().mediumAfterSeconds(),
                            manifest.config().highAfterSeconds()))) {
                throw new IllegalArgumentException("Invalid scene metadata for " + definition.scenarioId());
            }
            for (Reading row : rows) {
                if (!row.productType().equals(definition.productType()) || !row.region().equals(definition.region())
                        || !row.storageSessionStart().equals(definition.storageSessionStart())
                        || !row.labelRuleVersion().equals(definition.labelRuleVersion())
                        || row.scenarioType() != definition.scenarioType() || row.randomSeed() != definition.randomSeed()
                        || row.thresholdLowerC() != definition.thresholds().temperatureLowerC()
                        || row.thresholdUpperC() != definition.thresholds().temperatureUpperC()) {
                    throw new IllegalArgumentException("Reading differs from scene metadata for " + row.scenarioId());
                }
            }
            if (!traces.add(RiskScenarioQuality.validate(definition, rows).temperatureTrace())) {
                throw new IllegalArgumentException("Duplicate temperature trace across scenarios.");
            }
            scenes.add(new Scene(definition, List.copyOf(rows)));
        }
        if (!byId.isEmpty() || scenes.size() != manifest.scenarioCount()
                || scenes.stream().mapToLong(scene -> scene.rows().size()).sum() != manifest.recordCount()) {
            throw new IllegalArgumentException("Generated scenario counts do not match the manifest.");
        }
        return new Dataset(manifest, List.copyOf(scenes));
    }

    /** For each region, shuffle High and non-High scenes separately. Take one of each for each holdout. */
    static Map<Partition, List<Scene>> split(List<Scene> scenes, long seed) {
        var regions = new TreeMap<String, List<Scene>>();
        var ids = new HashSet<String>();
        for (Scene scene : scenes) {
            if (!ids.add(scene.definition().scenarioId())) {
                throw new IllegalArgumentException("Duplicate scenario ID.");
            }
            regions.computeIfAbsent(scene.definition().region(), key -> new ArrayList<>()).add(scene);
        }
        var result = new EnumMap<Partition, List<Scene>>(Partition.class);
        for (Partition partition : Partition.values()) {
            result.put(partition, new ArrayList<>());
        }
        var random = new Random(seed);
        for (var region : regions.entrySet()) {
            for (boolean high : List.of(true, false)) {
                var stratum = new ArrayList<>(region.getValue().stream().filter(scene -> scene.hasHigh() == high)
                        .sorted(Comparator.comparing(scene -> scene.definition().scenarioId())).toList());
                if (stratum.size() < 3) {
                    throw new IllegalArgumentException("Too few scenarios in stratum " + region.getKey() + " High=" + high);
                }
                Collections.shuffle(stratum, random);
                result.get(Partition.TEST).add(stratum.get(0));
                result.get(Partition.VALIDATION).add(stratum.get(1));
                result.get(Partition.TRAIN).addAll(stratum.subList(2, stratum.size()));
            }
        }
        result.replaceAll((partition, group) -> List.copyOf(group));
        result.forEach(ExpandedRiskData::requireClassCoverage);
        return Collections.unmodifiableMap(result);
    }

    private static void requireClassCoverage(Partition partition, List<Scene> scenes) {
        for (RiskLevel label : RiskLevel.values()) {
            long support = scenes.stream().filter(scene -> scene.rows().stream()
                    .anyMatch(row -> !row.incompleteHistory() && row.riskLevel() == label)).count();
            if (support < (label == RiskLevel.HIGH ? 2 : 1)) {
                throw new IllegalArgumentException(partition + " lacks independent support for " + label);
            }
        }
    }

    static RiskFeatures.Snapshot features(Reading row) {
        return new RiskFeatures.Snapshot(row.temperatureC(), row.humidityPct(), row.observedAboveUpperSeconds(),
                row.elapsedStorageSeconds(), row.productType(), row.incompleteHistory());
    }
}
