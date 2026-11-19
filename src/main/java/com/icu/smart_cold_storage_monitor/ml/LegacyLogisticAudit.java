package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.RiskTrainingData.Sample;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import weka.classifiers.functions.Logistic;
import weka.core.Instances;

/** Retrospective ML-1 diagnosis. It has no effect on ML-2 candidate selection. */
final class LegacyLogisticAudit {

    private LegacyLogisticAudit() {}

    static void run(Path output) throws Exception {
        Path modelPath = Path.of("target/ml-risk-training/multinomial-logistic.model");
        StringBuilder log = new StringBuilder("ML-1 retrospective diagnostic. Never used for ML-2 selection.\n");
        if (!Files.isRegularFile(modelPath)) {
            Files.writeString(output.resolve("legacy-logistic-audit.txt"),
                    log.append("Original saved model is absent. Audit skipped.\n"));
            return;
        }
        List<Sample> rows = RiskTrainingData.read(Path.of("data/monitoring_timestamped.jsonl"), new RiskRules(120, 600));
        List<Sample> train = rows.stream().filter(row -> !RiskTrainingData.TEST_REGIONS.contains(row.region())).toList();
        List<Sample> test = rows.stream().filter(row -> RiskTrainingData.TEST_REGIONS.contains(row.region())).toList();
        var legacy = WekaRiskModel.load(modelPath);
        Instances fitting = RiskTrainingMain.dataset(legacy.header(), train);
        Instances heldOut = RiskTrainingMain.dataset(legacy.header(), test);
        log.append("Raw header: ").append(legacy.header()).append('\n');
        log.append("Shared header equality: ").append(fitting.equalHeaders(heldOut)).append('\n');
        log.append("Logistic options: ").append(Arrays.toString(((Logistic) legacy.classifier()).getOptions())).append('\n');
        double maxCoefficient = Arrays.stream(((Logistic) legacy.classifier()).coefficients())
                .flatMapToDouble(Arrays::stream).map(Math::abs).max().orElseThrow();
        log.append(String.format(Locale.ROOT, "Maximum absolute coefficient: %.6f%n", maxCoefficient));
        for (int i = 0; i < 4; i++) {
            var training = fitting.attributeStats(i).numericStats;
            var testing = heldOut.attributeStats(i).numericStats;
            log.append(String.format(Locale.ROOT,
                    "%s train [%.4f, %.4f], mean %.4f; test [%.4f, %.4f], mean %.4f%n",
                    fitting.attribute(i).name(), training.min, training.max, training.mean,
                    testing.min, testing.max, testing.mean));
        }
        for (RiskLevel label : RiskLevel.values()) {
            log.append(label).append(" train regions: ").append(train.stream().filter(row -> row.label() == label)
                    .map(Sample::region).distinct().sorted().toList()).append('\n');
        }
        for (Sample sample : test.stream().filter(row -> row.label() == RiskLevel.LOW).limit(1).toList()) {
            log.append("First actual LOW probabilities: ").append(Arrays.toString(legacy.probabilities(sample.features())))
                    .append("; predicted ").append(legacy.predict(sample.features())).append('\n');
        }
        log.append("Original saved classifier:\n");
        score(log, legacy, test);
        Logistic regularized = new Logistic();
        regularized.setRidge(1);
        log.append("\nSensitivity check on the same original split (ridge=1):\n");
        score(log, WekaRiskModel.train(regularized, fitting), test);
        Files.writeString(output.resolve("legacy-logistic-audit.txt"), log);
    }

    private static void score(StringBuilder log, WekaRiskModel model, List<Sample> test) throws Exception {
        var metrics = new RiskMetrics();
        for (Sample row : test) {
            metrics.add(row.label(), model.predict(row.features()));
        }
        metrics.appendTo(log);
    }
}
