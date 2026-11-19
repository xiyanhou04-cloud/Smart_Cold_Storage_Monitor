package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.ExpandedRiskData.Partition;
import com.icu.smart_cold_storage_monitor.ml.ExpandedRiskData.Scene;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.functions.Logistic;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.Version;

/** ML-2 uses a separate dataset, output directory and immutable regional scene split. */
public final class ExpandedRiskTrainingMain {

    private static final int SEED = 5010;
    private static final String SPLIT_VERSION = "region-high-presence-v1";

    private ExpandedRiskTrainingMain() {}

    record SceneSplit(String scenarioId, String region, String productType, String scenarioType,
                      Map<RiskLevel, Long> labels, Map<RiskLevel, Long> completeLabels) {}
    record SplitSummary(int scenarios, long records, long completeRecords, Map<RiskLevel, Long> labels,
                        Map<RiskLevel, Long> completeLabels, Map<RiskLevel, Long> labelScenarioCounts,
                        List<SceneSplit> members) {}
    record Candidate(String description, Supplier<AbstractClassifier> create) {}
    record Trial(String setting, List<String> options, double validationSceneMacroF1, double fitMillis) {}
    record Manifest(String modelVersion, String featureVersion, List<String> featureOrder, List<String> units,
                    List<String> products, List<RiskLevel> classes, String javaVersion, String wekaVersion,
                    String dataSha256, String generatorVersion, String labelRuleVersion, RiskRules labelCutoffs,
                    long maximumObservedIntervalSeconds, String splitVersion, int seed, String chosenSetting,
                    List<String> classifierOptions, List<Trial> validationTrials) {}

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: ExpandedRiskTrainingMain [input-directory] [new-output-directory]");
        }
        Path input = Path.of(args.length > 0 ? args[0] : "target/ml-scenarios");
        Path output = Path.of(args.length > 1 ? args[1] : "target/ml-risk-expanded");
        ObjectMapper mapper = new ObjectMapper();
        var dataset = ExpandedRiskData.load(input, mapper);
        var split = ExpandedRiskData.split(dataset.scenes(), SEED);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output); // Each run has its own directory. Never replace earlier evidence.
        var splitSummary = new EnumMap<Partition, SplitSummary>(Partition.class);
        split.forEach((partition, scenes) -> splitSummary.put(partition, summarize(scenes)));
        writeJson(mapper, output.resolve("split.json"), splitSummary);

        List<Scene> training = split.get(Partition.TRAIN);
        List<Scene> validation = split.get(Partition.VALIDATION);
        List<Scene> testing = split.get(Partition.TEST);
        List<String> products = training.stream().map(scene -> scene.definition().productType()).distinct().sorted().toList();
        Instances header = WekaRiskModel.schema(products);
        header.setClassIndex(5);
        Instances fitting = instances(header, training);
        var development = new ArrayList<>(training);
        development.addAll(validation);
        Instances refitting = instances(header, development);
        var config = dataset.manifest().config();
        var rules = new RiskRules(config.mediumAfterSeconds(), config.highAfterSeconds());
        StringBuilder log = new StringBuilder();
        log.append("ML-2 simulated data SHA-256: ").append(dataset.manifest().dataSha256()).append('\n')
                .append("Split: ").append(SPLIT_VERSION).append("; seed: ").append(SEED).append('\n');
        splitSummary.forEach((partition, summary) -> log.append(partition).append(" scenes=").append(summary.scenarios())
                .append(" records=").append(summary.records()).append(" labels=").append(summary.labels())
                .append(" completeLabels=").append(summary.completeLabels())
                .append(" labelSceneCounts=").append(summary.labelScenarioCounts()).append('\n'));

        var forests = List.of(forest(60, 8), forest(100, 16), forest(100, 0));
        var logistics = List.of(logistic(1e-8), logistic(1e-4), logistic(0.01), logistic(1));
        train(log, output, mapper, dataset, "random-forest", forests, fitting, refitting, validation, testing, products, rules);
        train(log, output, mapper, dataset, "multinomial-logistic", logistics, fitting, refitting, validation, testing, products, rules);
        evaluate(log, output, mapper, "rules-baseline", testing, (scene, row) ->
                rules.predict(ExpandedRiskData.features(row), scene.definition().thresholds()));
        log.append("Rules training: N/A (fixed label policy)\n");
        Files.writeString(output.resolve("evaluation.txt"), log);
        System.out.print(log);
        LegacyLogisticAudit.run(output);
    }

    private static Candidate forest(int trees, int depth) {
        return new Candidate("trees=" + trees + ", maxDepth=" + depth, () -> {
            RandomForest model = new RandomForest();
            model.setSeed(SEED);
            model.setNumIterations(trees);
            model.setMaxDepth(depth);
            model.setNumExecutionSlots(1);
            return model;
        });
    }

    private static Candidate logistic(double ridge) {
        return new Candidate("ridge=" + ridge, () -> {
            Logistic model = new Logistic();
            model.setRidge(ridge);
            return model;
        });
    }

    private static void train(StringBuilder log, Path output, ObjectMapper mapper, ExpandedRiskData.Dataset dataset,
                              String name, List<Candidate> candidates, Instances fitting, Instances refitting,
                              List<Scene> validation, List<Scene> testing, List<String> products, RiskRules rules) throws Exception {
        Candidate selected = null;
        double best = Double.NEGATIVE_INFINITY;
        var trials = new ArrayList<Trial>();
        for (Candidate candidate : candidates) {
            AbstractClassifier classifier = candidate.create().get();
            long start = System.nanoTime();
            WekaRiskModel model = WekaRiskModel.train(classifier, fitting);
            double fitMillis = (System.nanoTime() - start) / 1_000_000.0;
            var trial = ExpandedRiskEvaluation.evaluate(validation, true,
                    (scene, row) -> model.predict(ExpandedRiskData.features(row)));
            double score = trial.meanMacroF1();
            trials.add(new Trial(candidate.description(), List.of(classifier.getOptions()), score, fitMillis));
            if (score > best) {
                selected = candidate;
                best = score;
            }
        }
        log.append('\n').append(name).append(" validation trials: ").append(trials).append('\n');
        AbstractClassifier classifier = selected.create().get();
        long start = System.nanoTime();
        WekaRiskModel model = WekaRiskModel.train(classifier, refitting);
        log.append(String.format(Locale.ROOT, "Final refit (%s, %d complete records): %.3f ms%n",
                selected.description(), refitting.numInstances(), (System.nanoTime() - start) / 1_000_000.0));
        Path artifact = output.resolve(name + ".model");
        model.save(artifact);
        Files.writeString(output.resolve(name + "-header.arff"), model.header().toString());
        var reloaded = WekaRiskModel.load(artifact);
        for (Scene scene : testing) {
            for (var row : scene.rows()) {
                if (!row.incompleteHistory()) {
                    var features = ExpandedRiskData.features(row);
                    if (!Arrays.equals(model.probabilities(features), reloaded.probabilities(features))
                            || model.predict(features) != reloaded.predict(features)) {
                        throw new IllegalStateException("Reloaded prediction differs for " + name);
                    }
                }
            }
        }
        writeJson(mapper, output.resolve(name + ".json"), new Manifest(
                name + "-ml2-v1", WekaRiskModel.FEATURE_VERSION,
                List.of("temperatureC", "humidityPct", "observedAboveUpperSeconds", "elapsedStorageSeconds", "productType"),
                List.of("C", "%RH", "s", "s", "nominal"), products, List.of(RiskLevel.values()),
                System.getProperty("java.version"), Version.VERSION, dataset.manifest().dataSha256(),
                dataset.manifest().config().generatorVersion(), RiskRules.VERSION, rules,
                RiskFeatures.MAX_OBSERVED_INTERVAL.toSeconds(), SPLIT_VERSION, SEED, selected.description(),
                List.of(classifier.getOptions()), trials));
        evaluate(log, output, mapper, name, testing, (scene, row) -> {
            var features = ExpandedRiskData.features(row);
            return row.incompleteHistory() ? rules.predict(features, scene.definition().thresholds()) : reloaded.predict(features);
        });
    }

    private static void evaluate(StringBuilder log, Path output, ObjectMapper mapper, String name,
                                 List<Scene> testing, ExpandedRiskEvaluation.Predictor predictor) throws Exception {
        var complete = ExpandedRiskEvaluation.evaluate(testing, true, predictor);
        var full = ExpandedRiskEvaluation.evaluate(testing, false, predictor);
        double latency = ExpandedRiskEvaluation.latencyP95(testing, predictor);
        log.append('\n').append(name).append(" - complete history\n");
        complete.appendTo(log);
        log.append(name).append(" - all records (rules for incomplete history)\n");
        full.appendTo(log);
        log.append(String.format(Locale.ROOT, "Full-path offline prediction P95: %.3f us%n", latency));
        writeJson(mapper, output.resolve(name + "-scenarios.json"),
                Map.of("completeHistory", complete.scenarios(), "allRecords", full.scenarios()));
    }

    private static Instances instances(Instances header, List<Scene> scenes) {
        Instances result = new Instances(header, 0);
        for (Scene scene : scenes) {
            for (var row : scene.rows()) {
                if (!row.incompleteHistory()) {
                    result.add(WekaRiskModel.encode(result, ExpandedRiskData.features(row), row.riskLevel()));
                }
            }
        }
        return result;
    }

    private static SplitSummary summarize(List<Scene> scenes) {
        var labels = zeroCounts();
        var completeLabels = zeroCounts();
        var sceneCounts = zeroCounts();
        var members = new ArrayList<SceneSplit>();
        long total = 0;
        long complete = 0;
        for (Scene scene : scenes) {
            var local = zeroCounts();
            var localComplete = zeroCounts();
            for (var row : scene.rows()) {
                total++;
                local.merge(row.riskLevel(), 1L, Long::sum);
                labels.merge(row.riskLevel(), 1L, Long::sum);
                if (!row.incompleteHistory()) {
                    complete++;
                    localComplete.merge(row.riskLevel(), 1L, Long::sum);
                    completeLabels.merge(row.riskLevel(), 1L, Long::sum);
                }
            }
            local.forEach((label, count) -> {
                if (count > 0) {
                    sceneCounts.merge(label, 1L, Long::sum);
                }
            });
            var definition = scene.definition();
            members.add(new SceneSplit(definition.scenarioId(), definition.region(), definition.productType(),
                    definition.scenarioType().name(), local, localComplete));
        }
        return new SplitSummary(scenes.size(), total, complete, labels, completeLabels, sceneCounts, members);
    }

    private static EnumMap<RiskLevel, Long> zeroCounts() {
        var counts = new EnumMap<RiskLevel, Long>(RiskLevel.class);
        for (RiskLevel label : RiskLevel.values()) {
            counts.put(label, 0L);
        }
        return counts;
    }

    private static void writeJson(ObjectMapper mapper, Path file, Object object) throws Exception {
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(object));
    }
}
