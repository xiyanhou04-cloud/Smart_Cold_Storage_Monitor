package com.icu.smart_cold_storage_monitor.ml;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;
import weka.classifiers.trees.RandomForest;
import weka.core.Instance;

class WekaRiskPredictorTest {

    private static final String BASE = "classpath:/risk-model/random-forest";
    private static final String VERSION = "random-forest-ml2-v1";
    private static final ThresholdLimits LIMITS = new ThresholdLimits(0, 4, 55, 80);
    private final DefaultResourceLoader loader = new DefaultResourceLoader();
    private final RiskFeatures.Snapshot normal = new RiskFeatures.Snapshot(3, 70, 0, 5, "RAW_MEAT", false);

    @TempDir Path directory;

    @Test
    void modelAndFallbackReportDistinctQuality() {
        var valid = predictor(BASE, VERSION);
        assertThat(valid.predict(normal, LIMITS).source()).isEqualTo(AlertSource.MODEL);
        assertThat(predictor("classpath:/absent", VERSION).predict(normal, LIMITS).quality())
                .isEqualTo(RiskPredictor.Quality.MODEL_MISSING);
        assertThat(predictor(BASE, "wrong-version").predict(normal, LIMITS).quality())
                .isEqualTo(RiskPredictor.Quality.MODEL_INCOMPATIBLE);
        var unknown = new RiskFeatures.Snapshot(5, 70, 0, 5, "UNKNOWN", false);
        var result = valid.predict(unknown, LIMITS);
        assertThat(result.source()).isEqualTo(AlertSource.RULES);
        assertThat(result.quality()).isEqualTo(RiskPredictor.Quality.UNKNOWN_PRODUCT);
        assertThat(result.level()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void classifierExceptionFallsBackToRules() throws Exception {
        WekaRiskModel original;
        try (var input = loader.getResource(BASE + ".model").getInputStream()) {
            original = WekaRiskModel.load(input);
        }
        var faulty = new FailingForest();
        faulty.setOptions(((RandomForest) original.classifier()).getOptions());
        new WekaRiskModel(faulty, original.header()).save(directory.resolve("test.model"));
        for (String suffix : new String[]{".json", "-header.arff"}) {
            try (var input = loader.getResource(BASE + suffix).getInputStream()) {
                Files.copy(input, directory.resolve("test" + suffix));
            }
        }
        var result = predictor(directory.toUri() + "test", VERSION).predict(normal, LIMITS);
        assertThat(result.source()).isEqualTo(AlertSource.RULES);
        assertThat(result.quality()).isEqualTo(RiskPredictor.Quality.MODEL_ERROR);
        assertThat(result.level()).isEqualTo(RiskLevel.LOW);
    }

    private WekaRiskPredictor predictor(String base, String version) {
        return new WekaRiskPredictor(loader, new ObjectMapper(), new RulesRiskPredictor(120, 600), base, version);
    }

    public static final class FailingForest extends RandomForest {
        @Override
        public double[] distributionForInstance(Instance instance) {
            throw new IllegalStateException("injected classifier failure");
        }
    }
}
