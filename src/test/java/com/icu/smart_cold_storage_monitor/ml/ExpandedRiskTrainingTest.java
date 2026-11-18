package com.icu.smart_cold_storage_monitor.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.icu.smart_cold_storage_monitor.ml.ExpandedRiskData.Partition;
import com.icu.smart_cold_storage_monitor.ml.ExpandedRiskData.Scene;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ExpandedRiskTrainingTest {

    @TempDir Path directory;

    @Test
    void freezesWholeSceneSplitsWithAllClassesAndMultipleHighScenes() throws Exception {
        RiskScenarioGenerationMain.run(Path.of("testData/ML_Scenarios/generator-config.json"), directory);
        var data = ExpandedRiskData.load(directory, new ObjectMapper());
        var split = ExpandedRiskData.split(data.scenes(), 5010);
        assertEquals(split, ExpandedRiskData.split(data.scenes().reversed(), 5010));
        assertEquals(42, split.get(Partition.TRAIN).size());
        assertEquals(14, split.get(Partition.VALIDATION).size());
        assertEquals(14, split.get(Partition.TEST).size());
        assertEquals(7, split.get(Partition.TEST).stream().filter(Scene::hasHigh).count());
        var ids = new HashSet<String>();
        long rows = 0;
        for (var partition : split.values()) {
            for (var scene : partition) {
                assertTrue(ids.add(scene.definition().scenarioId()));
                rows += scene.rows().size();
            }
        }
        assertEquals(70, ids.size());
        assertEquals(40_528, rows);
        assertThrows(IllegalArgumentException.class, () -> ExpandedRiskData.split(List.of(data.scenes().getFirst()), 5010));
        Files.writeString(directory.resolve("generated-readings.jsonl"), "tampered");
        assertThrows(IllegalArgumentException.class, () -> ExpandedRiskData.load(directory, new ObjectMapper()));
    }

    @Test
    void macroF1IsClassBalancedAndUsesOnlySupportedClasses() {
        var score = new RiskMetrics();
        for (int i = 0; i < 9; i++) {
            score.add(RiskLevel.LOW, RiskLevel.LOW);
        }
        score.add(RiskLevel.MEDIUM, RiskLevel.LOW);
        assertEquals(0.9, score.accuracy());
        assertEquals(9.0 / 19, score.macroF1(), 1e-12);
    }
}
