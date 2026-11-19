package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.RiskTrainingData.Sample;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.functions.Logistic;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.Version;

/** Runs the fixed regional holdout experiment without creating a Spring application context. */
public final class RiskTrainingMain {

    static final int SEED = 5010;
    private static final RiskRules DEFAULT_RULES = new RiskRules(120, 600);
    private static final int LATENCY_PASSES = 10;

    private RiskTrainingMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: RiskTrainingMain [fixture.jsonl [output-directory [mediumSeconds highSeconds]]]");
        }
        Path source = Path.of(args.length > 0 ? args[0] : "data/monitoring_timestamped.jsonl");
        Path output = Path.of(args.length > 1 ? args[1] : "target/ml-risk-training");
        RiskRules rules = args.length == 4
                ? new RiskRules(Long.parseLong(args[2]), Long.parseLong(args[3])) : DEFAULT_RULES;
        List<Sample> samples = RiskTrainingData.read(source, rules);
        List<Sample> train = samples.stream().filter(sample -> !heldOut(sample)).toList();
        List<Sample> test = samples.stream().filter(RiskTrainingMain::heldOut).toList();
        List<String> products = train.stream().map(sample -> sample.features().productType()).distinct().sorted().toList();
        Instances schema = WekaRiskModel.schema(products);
        schema.setClassIndex(5);
        Instances training = dataset(schema, train);
        Instances testing = dataset(schema, test);
        if (train.stream().map(Sample::label).distinct().count() != RiskLevel.values().length || test.isEmpty()) {
            throw new IllegalArgumentException("Expected three training classes and a nonempty regional holdout.");
        }

        Files.createDirectories(output);
        Files.writeString(output.resolve("train.arff"), training.toString());
        Files.writeString(output.resolve("test.arff"), testing.toString());
        var mapper = new ObjectMapper();
        Files.writeString(output.resolve("dataset.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(samples));

        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        StringBuilder log = new StringBuilder();
        log.append("Data SHA-256: ").append(hash).append('\n');
        log.append("Java: ").append(System.getProperty("java.version")).append("; Weka: ").append(Version.VERSION).append('\n');
        log.append("Seed: ").append(SEED).append("; rule: ").append(RiskRules.VERSION)
                .append(" (").append(rules.mediumAfterSeconds()).append(" s Medium, ")
                .append(rules.highAfterSeconds()).append(" s High)\n");
        log.append("Classes: ").append(Arrays.toString(RiskLevel.values())).append("; products: ").append(products).append('\n');
        describeSplit(log, "Train", train);
        describeSplit(log, "Test", test);

        RandomForest forest = new RandomForest();
        forest.setSeed(SEED);
        forest.setNumIterations(100);
        forest.setNumExecutionSlots(1);
        evaluateModel(log, output, "random-forest", forest, training, test, products, hash, rules, mapper);

        Logistic logistic = new Logistic();
        evaluateModel(log, output, "multinomial-logistic", logistic, training, test, products, hash, rules, mapper);

        evaluate(log, "rules-baseline", "N/A (fixed policy)", test,
                sample -> rules.predict(sample.features(), sample.limits()));

        Files.writeString(output.resolve("evaluation.txt"), log);
        System.out.print(log);
    }

    private static boolean heldOut(Sample sample) {
        return RiskTrainingData.TEST_REGIONS.contains(sample.region());
    }

    static Instances dataset(Instances schema, List<Sample> samples) {
        Instances instances = new Instances(schema, samples.size());
        for (Sample sample : samples) {
            instances.add(WekaRiskModel.encode(instances, sample.features(), sample.label()));
        }
        return instances;
    }

    private static void describeSplit(StringBuilder log, String name, List<Sample> samples) {
        log.append(name).append(" (").append(samples.size()).append("): ")
                .append(samples.stream().map(s -> s.region() + "=" + s.session()).distinct().sorted().toList()).append('\n');
        for (RiskLevel level : RiskLevel.values()) {
            log.append("  ").append(level).append(": ")
                    .append(samples.stream().filter(sample -> sample.label() == level).count()).append('\n');
        }
    }

    private record Manifest(String modelVersion, String wekaVersion, String javaVersion, String featureVersion,
                            List<String> featureOrder, List<String> featureUnits, List<String> products,
                            List<RiskLevel> classes, int seed, List<String> classifierOptions, String datasetSha256,
                            String ruleVersion, RiskRules cutoffs, long maximumObservedIntervalSeconds,
                            String thresholdSource) {}

    private static void evaluateModel(StringBuilder log, Path output, String name, AbstractClassifier classifier,
                                      Instances training, List<Sample> test, List<String> products,
                                      String hash, RiskRules rules, ObjectMapper mapper) throws Exception {
        long start = System.nanoTime();
        WekaRiskModel model = WekaRiskModel.train(classifier, training);
        String trainingTime = String.format(Locale.ROOT, "%.3f ms", (System.nanoTime() - start) / 1_000_000.0);
        Path artifact = output.resolve(name + ".model");
        model.save(artifact);
        WekaRiskModel reloaded = WekaRiskModel.load(artifact);
        for (Sample sample : test) {
            if (model.predict(sample.features()) != reloaded.predict(sample.features())) {
                throw new IllegalStateException("Model prediction changed after reloading " + name);
            }
        }

        var manifest = new Manifest(name + "-v1", Version.VERSION, System.getProperty("java.version"),
                WekaRiskModel.FEATURE_VERSION,
                List.of("temperatureC", "humidityPct", "observedAboveUpperSeconds", "elapsedStorageSeconds", "productType"),
                List.of("C", "%RH", "s", "s", "nominal"), products, List.of(RiskLevel.values()),
                SEED, List.of(classifier.getOptions()), hash, RiskRules.VERSION, rules,
                RiskFeatures.MAX_OBSERVED_INTERVAL.toSeconds(), "application.yaml app.thresholds.locations");
        Files.writeString(output.resolve(name + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));

        log.append(name).append(" options: ").append(Arrays.toString(classifier.getOptions())).append('\n');
        evaluate(log, name, trainingTime, test, sample -> reloaded.predict(sample.features()));
    }

    private static void evaluate(StringBuilder log, String name, String trainingTime, List<Sample> test,
                                 Predictor predictor) throws Exception {
        RiskMetrics metrics = new RiskMetrics();
        for (Sample sample : test) {
            metrics.add(sample.label(), predictor.predict(sample));
        }

        long[] timings = new long[test.size() * LATENCY_PASSES];
        int index = 0;
        for (int pass = 0; pass < LATENCY_PASSES; pass++) {
            for (Sample sample : test) {
                long start = System.nanoTime();
                predictor.predict(sample);
                timings[index++] = System.nanoTime() - start;
            }
        }
        Arrays.sort(timings);
        log.append(String.format(Locale.ROOT,
                "%n%s%nTraining: %s%nInference latency (n=%d, excluding history aggregation and I/O): mean %.3f us; p95 %.3f us%n",
                name, trainingTime, timings.length,
                Arrays.stream(timings).average().orElseThrow() / 1000,
                timings[(int) Math.ceil(timings.length * 0.95) - 1] / 1000.0));
        metrics.appendTo(log);
    }

    @FunctionalInterface
    private interface Predictor {
        RiskLevel predict(Sample sample) throws Exception;
    }
}
