package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.RiskFeatures.Snapshot;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.nio.file.Path;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SerializationHelper;
import weka.core.Utils;

/** Offline training and inference share one positional Weka schema. */
public record WekaRiskModel(Classifier classifier, Instances header) {

    static final String FEATURE_VERSION = "risk-features-v1";

    static Instances schema(List<String> products) {
        return new Instances(FEATURE_VERSION, new ArrayList<>(List.of(
                new Attribute("temperatureC"), new Attribute("humidityPct"),
                new Attribute("observedAboveUpperSeconds"), new Attribute("elapsedStorageSeconds"),
                new Attribute("productType", products),
                new Attribute("riskLevel", Arrays.stream(RiskLevel.values()).map(Enum::name).toList()))), 0);
    }

    static Instance encode(Instances schema, Snapshot snapshot, RiskLevel label) {
        int product = schema.attribute("productType").indexOfValue(snapshot.productType());
        if (snapshot.incompleteHistory() || product < 0) {
            throw new IllegalArgumentException("A supported product and complete exposure history are required.");
        }
        Instance instance = new DenseInstance(1, new double[] {
                snapshot.temperatureC(), snapshot.humidityPct(), snapshot.observedAboveUpperSeconds(),
                snapshot.elapsedStorageSeconds(), product, label == null ? Utils.missingValue() : label.ordinal()
        });
        instance.setDataset(schema);
        return instance;
    }

    static WekaRiskModel train(Classifier classifier, Instances training) throws Exception {
        classifier.buildClassifier(training);
        return new WekaRiskModel(classifier, new Instances(training, 0));
    }

    public RiskLevel predict(Snapshot snapshot) throws Exception {
        double index = classifier.classifyInstance(encode(header, snapshot, null));
        if (!Double.isFinite(index) || index != Math.rint(index) || index < 0 || index >= header.numClasses()) {
            throw new IllegalStateException("Classifier returned no valid risk class.");
        }
        return RiskLevel.valueOf(header.classAttribute().value((int) index));
    }

    double[] probabilities(Snapshot snapshot) throws Exception {
        return classifier.distributionForInstance(encode(header, snapshot, null));
    }

    void save(Path path) throws Exception {
        SerializationHelper.writeAll(path.toString(), new Object[] { classifier, header });
    }

    /** Load only a project-generated model. Java serialization requires trusted input. */
    public static WekaRiskModel load(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return load(input);
        }
    }

    static WekaRiskModel load(InputStream input) throws Exception {
        Object[] objects = SerializationHelper.readAll(input);
        if (objects.length != 2 || !(objects[0] instanceof Classifier classifier)
                || !(objects[1] instanceof Instances header) || header.numAttributes() != 6
                || header.classIndex() != 5 || !header.attribute(4).isNominal()) {
            throw new IllegalArgumentException("Invalid risk model artifact.");
        }
        List<String> products = java.util.Collections.list(header.attribute(4).enumerateValues())
                .stream().map(Object::toString).toList();
        Instances expected = schema(products);
        expected.setClassIndex(5);
        if (!FEATURE_VERSION.equals(header.relationName()) || !expected.equalHeaders(header)) {
            throw new IllegalArgumentException("Incompatible risk feature schema.");
        }
        return new WekaRiskModel(classifier, header);
    }
}
