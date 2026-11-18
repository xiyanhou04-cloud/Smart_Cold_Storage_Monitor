package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.repository.PredictionRepository;
import com.icu.smart_cold_storage_monitor.repository.StorageSessionRepository;
import com.icu.smart_cold_storage_monitor.repository.ThresholdSettingRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReadingRiskService {

    private static final Logger log = LoggerFactory.getLogger(ReadingRiskService.class);
    private final PredictionRepository predictions;
    private final StorageSessionRepository sessions;
    private final ThresholdSettingRepository thresholds;
    private final RiskPredictor predictor;
    private final RulesRiskPredictor rules;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReadingRiskService(PredictionRepository predictions, StorageSessionRepository sessions,
                              ThresholdSettingRepository thresholds, WekaRiskPredictor predictor,
                              RulesRiskPredictor rules, ObjectMapper mapper, Clock clock) {
        this.predictions = predictions;
        this.sessions = sessions;
        this.thresholds = thresholds;
        this.predictor = predictor;
        this.rules = rules;
        this.mapper = mapper;
        this.clock = clock;
    }

    record SavedFeatures(RiskFeatures.Snapshot features, Long thresholdId, RiskRules ruleCutoffs) {}

    void predictReading(Reading reading) {
        try {
            compute(reading);
        } catch (RuntimeException ex) {
            log.error("Could not save risk prediction for reading {}. It remains pending.", reading.id(), ex);
        }
    }

    private void compute(Reading reading) {
        if (predictions.exists(reading.id())) {
            return;
        }
        var session = sessions.findById(reading.sessionId()).orElseThrow();
        String product = RiskTrainingData.REGION_PRODUCTS.getOrDefault(session.productType(), session.productType());
        var accumulator = new RiskFeatures(session.startedAt(), product);
        Instant after = null;
        var checkpoint = predictions.precedingCheckpoint(reading);
        if (checkpoint.isPresent()) {
            var prior = checkpoint.get();
            try {
                var saved = mapper.readValue(prior.featureSnapshot(), SavedFeatures.class);
                if (saved.features() != null && product.equals(saved.features().productType())) {
                    accumulator = RiskFeatures.resume(session.startedAt(), saved.features(),
                            prior.reading().measuredAt(), limits(prior.reading()));
                    after = prior.reading().measuredAt();
                }
            } catch (JacksonException ex) {
                log.warn("Replaying reading history after an unreadable feature snapshot for {}", prior.reading().id(), ex);
            }
        }

        RiskFeatures.Snapshot snapshot = null;
        ThresholdLimits currentLimits = null;
        for (Reading item : predictions.historyThrough(reading, after)) {
            currentLimits = limits(item);
            snapshot = accumulator.accept(item.measuredAt(), item.temperatureC(), item.humidityPct(), currentLimits);
        }
        if (snapshot == null) {
            return;
        }
        var estimate = predictor.predict(snapshot, currentLimits);
        var saved = new SavedFeatures(snapshot, reading.thresholdId(), rules.configuration());
        predictions.insert(reading.id(), estimate, clock.instant(), mapper.writeValueAsString(saved));
    }

    private ThresholdLimits limits(Reading reading) {
        return reading.thresholdId() == null ? null : thresholds.findById(reading.thresholdId())
                .map(setting -> setting.limits()).orElse(null);
    }
}
