package com.icu.smart_cold_storage_monitor.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Mode;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RiskScenarioGenerationTest {

    private static final Path CONFIG = Path.of("testData/ML_Scenarios/generator-config.json");

    @TempDir Path output;

    @Test
    void regeneratesIdenticalFilesWithCoverageAcrossRegions() throws Exception {
        Path first = output.resolve("first");
        Path second = output.resolve("second");
        var manifest = RiskScenarioGenerationMain.run(CONFIG, first);
        var repeated = RiskScenarioGenerationMain.run(CONFIG, second);
        assertEquals(manifest.dataSha256(), repeated.dataSha256());
        for (String file : Set.of("generated-readings.jsonl", "scenario-manifest.json")) {
            assertEquals(-1, Files.mismatch(first.resolve(file), second.resolve(file)), file);
        }

        assertEquals(70, manifest.scenarioCount());
        assertEquals(7, manifest.regions().size());
        assertEquals(7, manifest.scenarioTypes().size());
        assertTrue(manifest.regions().values().stream().allMatch(count -> count.scenarios() == 10));
        assertEquals(7, manifest.gapIntervals());
        assertEquals(0, manifest.duplicateTemperatureTraces());
        assertEquals(Set.of("RAW_MEAT", "EGGS", "PRODUCE", "FRUIT"), manifest.highProducts());
        assertEquals(7, manifest.highRegions().size());
        assertTrue(manifest.highScenarioTypes().containsAll(
                Set.of(Mode.SUSTAINED_EXCURSION, Mode.GRADUAL_WARMING, Mode.REPEATED_EXCURSIONS)));
        assertTrue(manifest.labels().values().stream().allMatch(count -> count.scenarios() > 1));
        assertEquals(manifest.recordCount(), manifest.labels().values().stream()
                .mapToLong(RiskScenarioGenerationMain.Count::records).sum());
        try (var rows = Files.lines(first.resolve("generated-readings.jsonl"))) {
            assertEquals(manifest.recordCount(), rows.count());
        }
    }

    @Test
    void warmGapSkipsExposureAndDuplicateTimestampFailsAudit() {
        var limits = new ThresholdLimits(0, 4, 55, 80);
        var scenario = RiskScenarioGenerator.plan("gap", 50, "RAW-MEAT", Instant.EPOCH,
                Mode.DATA_GAP, limits, 5, new RiskRules(120, 600));
        var rows = RiskScenarioGenerator.generate(scenario);
        var audit = RiskScenarioQuality.validate(scenario, rows);
        assertEquals(1, audit.gapIntervals());
        int gap = 1;
        while (!rows.get(gap).gapFromPrevious()) {
            gap++;
        }
        assertTrue(rows.get(gap - 1).temperatureC() > limits.temperatureUpperC());
        assertEquals(rows.get(gap - 1).observedAboveUpperSeconds(), rows.get(gap).observedAboveUpperSeconds());
        assertTrue(rows.getLast().incompleteHistory());
        assertEquals(RiskLevel.MEDIUM, rows.getLast().riskLevel());

        var corrupted = new ArrayList<>(rows);
        corrupted.set(1, corrupted.getFirst());
        assertThrows(IllegalStateException.class, () -> RiskScenarioQuality.validate(scenario, corrupted));
    }

    @Test
    void configRejectsInvalidCadenceAndCutoffs() {
        assertThrows(IllegalArgumentException.class, () -> new RiskScenarioGenerationMain.Config(
                RiskScenarioGenerator.VERSION, 0, Instant.EPOCH, 31, 120, 600));
        assertThrows(IllegalArgumentException.class, () -> new RiskScenarioGenerationMain.Config(
                RiskScenarioGenerator.VERSION, 0, Instant.EPOCH, 5, 600, 120));
    }
}
