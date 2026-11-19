package com.icu.smart_cold_storage_monitor.ml;

import static org.junit.jupiter.api.Assertions.*;

import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import weka.classifiers.Classifier;
import weka.classifiers.functions.Logistic;
import weka.classifiers.trees.RandomForest;

class WekaRiskModelTest {

    @TempDir Path temp;

    @Test
    void usesTheSameFiveFeatureValuesForTrainingAndInference() {
        var schema = WekaRiskModel.schema(List.of("PRODUCE", "RAW_MEAT"));
        schema.setClassIndex(5);
        var snapshot = snapshot(3);
        var training = WekaRiskModel.encode(schema, snapshot, RiskLevel.LOW);
        var inference = WekaRiskModel.encode(schema, snapshot, null);
        assertArrayEquals(Arrays.copyOf(training.toDoubleArray(), 5), Arrays.copyOf(inference.toDoubleArray(), 5));
        assertArrayEquals(new double[] {5, 70, 15, 30, 1}, Arrays.copyOf(inference.toDoubleArray(), 5));
        assertTrue(inference.classIsMissing());
        assertThrows(IllegalArgumentException.class, () -> WekaRiskModel.encode(schema,
                new RiskFeatures.Snapshot(5, 70, 15, 30, "UNKNOWN", false), null));
        assertThrows(IllegalArgumentException.class, () -> WekaRiskModel.encode(schema,
                new RiskFeatures.Snapshot(5, 70, 15, 30, "RAW_MEAT", true), null));
    }

    @Test
    void reloadsBothClassifiersAndPreservesPredictions() throws Exception {
        var train = WekaRiskModel.schema(List.of("RAW_MEAT"));
        train.setClassIndex(5);
        for (int i = 0; i < 18; i++) {
            train.add(WekaRiskModel.encode(train, snapshot(i), RiskLevel.values()[i / 6]));
        }
        for (Classifier classifier : List.of(new RandomForest(), new Logistic())) {
            var original = WekaRiskModel.train(classifier, train);
            Path file = temp.resolve(classifier.getClass().getSimpleName() + ".model");
            original.save(file);
            var loaded = WekaRiskModel.load(file);
            assertTrue(original.header().equalHeaders(loaded.header()));
            assertEquals(0, loaded.header().numInstances());
            for (int i = 0; i < 18; i++) {
                assertEquals(original.predict(snapshot(i)), loaded.predict(snapshot(i)));
            }
        }
    }

    @Test
    void runsOfflineExperimentAndReportsMissingHighSupport() throws Exception {
        RiskTrainingMain.main(new String[] {"data/monitoring_timestamped.jsonl", temp.toString()});
        String results = Files.readString(temp.resolve("evaluation.txt"));
        assertTrue(results.contains("Train (2166)"));
        assertTrue(results.contains("Test (361)"));
        assertEquals(3, results.lines().filter(line -> line.contains("HIGH support=0")).count());
        assertTrue(Files.isRegularFile(temp.resolve("random-forest.model")));
        assertTrue(Files.isRegularFile(temp.resolve("multinomial-logistic.model")));
    }

    private static RiskFeatures.Snapshot snapshot(int index) {
        return new RiskFeatures.Snapshot(index + 2, 70, index * 5, index * 10, "RAW_MEAT", false);
    }
}
