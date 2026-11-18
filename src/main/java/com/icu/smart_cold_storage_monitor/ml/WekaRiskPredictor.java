package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.Version;

@Component
public final class WekaRiskPredictor implements RiskPredictor {

    private static final Logger log = LoggerFactory.getLogger(WekaRiskPredictor.class);
    private static final List<String> FEATURES = List.of(
            "temperatureC", "humidityPct", "observedAboveUpperSeconds", "elapsedStorageSeconds", "productType");
    private static final List<String> UNITS = List.of("C", "%RH", "s", "s", "nominal");

    private final RulesRiskPredictor rules;
    private final WekaRiskModel model;
    private final String version;
    private final Quality unavailable;

    public WekaRiskPredictor(ResourceLoader loader, ObjectMapper mapper, RulesRiskPredictor rules,
                             @Value("${app.risk.model-base}") String modelBase,
                             @Value("${app.risk.model-version}") String expectedVersion) {
        this.rules = rules;
        version = expectedVersion;
        Resource binary = loader.getResource(modelBase + ".model");
        Resource metadata = loader.getResource(modelBase + ".json");
        Resource header = loader.getResource(modelBase + "-header.arff");
        WekaRiskModel loaded = null;
        Quality failure = Quality.MODEL_MISSING;
        if (binary.exists() && metadata.exists() && header.exists()) {
            failure = Quality.MODEL_INCOMPATIBLE;
            try (var json = metadata.getInputStream(); var modelInput = binary.getInputStream();
                 var arff = new BufferedReader(new InputStreamReader(header.getInputStream(), StandardCharsets.UTF_8))) {
                var document = mapper.readTree(json);
                if (!expectedVersion.equals(document.path("modelVersion").asString())
                        || !Version.VERSION.equals(document.path("wekaVersion").asString())
                        || !WekaRiskModel.FEATURE_VERSION.equals(document.path("featureVersion").asString())
                        || !RiskRules.VERSION.equals(document.path("labelRuleVersion").asString())
                        || document.path("labelCutoffs").path("mediumAfterSeconds").asLong() != rules.configuration().mediumAfterSeconds()
                        || document.path("labelCutoffs").path("highAfterSeconds").asLong() != rules.configuration().highAfterSeconds()
                        || document.path("maximumObservedIntervalSeconds").asLong() != RiskFeatures.MAX_OBSERVED_INTERVAL.toSeconds()
                        || !mapper.valueToTree(FEATURES).equals(document.path("featureOrder"))
                        || !mapper.valueToTree(UNITS).equals(document.path("units"))
                        || !mapper.valueToTree(List.of(RiskLevel.values())).equals(document.path("classes"))) {
                    throw new IllegalArgumentException("Model version or feature metadata does not match the runtime.");
                }
                Instances externalHeader = new Instances(arff);
                externalHeader.setClassIndex(FEATURES.size());
                WekaRiskModel candidate = WekaRiskModel.load(modelInput);
                var products = Collections.list(candidate.header().attribute("productType").enumerateValues());
                if (!(candidate.classifier() instanceof RandomForest forest)
                        || !mapper.valueToTree(Arrays.asList(forest.getOptions())).equals(document.path("classifierOptions"))
                        || !candidate.header().equalHeaders(externalHeader) || externalHeader.numInstances() != 0
                        || !externalHeader.relationName().equals(candidate.header().relationName())
                        || !mapper.valueToTree(products).equals(document.path("products"))) {
                    throw new IllegalArgumentException("Classifier or Weka headers do not match the metadata.");
                }
                loaded = candidate;
                log.info("Loaded storage risk model {}", version);
            } catch (Exception | LinkageError ex) {
                log.warn("Storage risk model {} is incompatible. Using rules.", version, ex);
            }
        } else {
            log.warn("Storage risk model artifacts are missing at {}. Using rules.", modelBase);
        }
        model = loaded;
        unavailable = failure;
    }

    @Override
    public Estimate predict(RiskFeatures.Snapshot features, ThresholdLimits currentLimits) {
        if (features.incompleteHistory()) {
            return rules.fallback(features, currentLimits, Quality.INCOMPLETE_HISTORY);
        }
        if (model == null) {
            return rules.fallback(features, currentLimits, unavailable);
        }
        if (model.header().attribute("productType").indexOfValue(features.productType()) < 0) {
            return rules.fallback(features, currentLimits, Quality.UNKNOWN_PRODUCT);
        }
        try {
            return new Estimate(model.predict(features), AlertSource.MODEL, version, Quality.OK);
        } catch (Exception | LinkageError ex) {
            log.warn("Storage risk inference failed. Using rules.", ex);
            return rules.fallback(features, currentLimits, Quality.MODEL_ERROR);
        }
    }
}
