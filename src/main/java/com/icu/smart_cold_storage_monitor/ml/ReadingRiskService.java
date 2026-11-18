package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.repository.PredictionRepository;
import com.icu.smart_cold_storage_monitor.repository.StorageSessionRepository;
import com.icu.smart_cold_storage_monitor.repository.ThresholdSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
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
    private final RiskMonitoringRuns monitoringRuns;

    public ReadingRiskService(PredictionRepository predictions, StorageSessionRepository sessions,
                              ThresholdSettingRepository thresholds, WekaRiskPredictor predictor,
                              RulesRiskPredictor rules, ObjectMapper mapper, Clock clock, RiskMonitoringRuns monitoringRuns) {
        this.predictions = predictions;
        this.sessions = sessions;
        this.thresholds = thresholds;
        this.predictor = predictor;
        this.rules = rules;
        this.mapper = mapper;
        this.clock = clock;
        this.monitoringRuns = monitoringRuns;
    }

    record SavedFeatures(RiskFeatures.Snapshot features, Long thresholdId, RiskRules ruleCutoffs) {}

    void predictReading(Reading reading) {
        if (predictions.exists(reading.id())) {
            return;
        }
        var session = sessions.findById(reading.sessionId()).orElseThrow();
        String product = RiskTrainingData.REGION_PRODUCTS.getOrDefault(session.productType(), session.productType());
        var accumulator = new RiskFeatures(session.startedAt(), product);
        Instant after = null;
        Instant previousReceipt = null;
        var checkpoint = predictions.precedingCheckpoint(reading);
        if (checkpoint.isPresent()) {
            var prior = checkpoint.get();
            try {
                var document = mapper.readTree(prior.featureSnapshot());
                var fields = document.path("features");
                var saved = mapper.readValue(prior.featureSnapshot(), SavedFeatures.class);
                if (fields.path("observedAboveUpperSeconds").isNumber()
                        && fields.path("elapsedStorageSeconds").isNumber()
                        && fields.path("temperatureC").isNumber() && fields.path("humidityPct").isNumber()
                        && fields.path("incompleteHistory").isBoolean()
                        && usableCheckpoint(saved, prior.reading(), session.startedAt(), product)) {
                    accumulator = RiskFeatures.resume(session.startedAt(), saved.features(),
                            prior.reading().measuredAt(), limits(prior.reading()));
                    after = prior.reading().measuredAt();
                    previousReceipt = prior.reading().receivedAt();
                }
            } catch (JacksonException ex) {
                log.warn("Replaying reading history after an unreadable feature snapshot for {}", prior.reading().id(), ex);
            }
        }

        RiskFeatures.Snapshot snapshot = null;
        ThresholdLimits currentLimits = null;
        for (Reading item : predictions.historyThrough(reading, after)) {
            currentLimits = limits(item);
            var interruption = accumulator.previousAt() == null ? RiskFeatures.GapReason.NONE
                    : monitoringRuns.interruptionBetween(accumulator.previousAt(), item.measuredAt(),
                            previousReceipt, item.receivedAt());
            snapshot = accumulator.accept(item.measuredAt(), item.temperatureC(), item.humidityPct(), currentLimits, interruption);
            previousReceipt = item.receivedAt();
        }
        if (snapshot == null) {
            return;
        }
        var estimate = predictor.predict(snapshot, currentLimits);
        var saved = new SavedFeatures(snapshot, reading.thresholdId(), rules.configuration());
        predictions.insert(reading.id(), estimate, clock.instant(), mapper.writeValueAsString(saved));
    }

    private static boolean usableCheckpoint(SavedFeatures saved, Reading reading, Instant startedAt, String product) {
        var snapshot = saved.features();
        if (snapshot == null || !product.equals(snapshot.productType())
                || !Objects.equals(saved.thresholdId(), reading.thresholdId())
                || snapshot.temperatureC() != reading.temperatureC() || snapshot.humidityPct() != reading.humidityPct()
                || !Double.isFinite(snapshot.observedAboveUpperSeconds())
                || !Double.isFinite(snapshot.elapsedStorageSeconds())) {
            return false;
        }
        long elapsedMs = Duration.between(startedAt, reading.measuredAt()).toMillis();
        return elapsedMs >= 0 && Math.round(snapshot.elapsedStorageSeconds() * 1000) == elapsedMs
                && snapshot.observedAboveUpperSeconds() >= 0
                && snapshot.observedAboveUpperSeconds() <= snapshot.elapsedStorageSeconds()
                && snapshot.incompleteHistory() == (snapshot.lastGapReason() != RiskFeatures.GapReason.NONE)
                && (snapshot.currentIssue() == RiskFeatures.GapReason.NONE
                    || snapshot.currentIssue() == snapshot.lastGapReason());
    }

    private ThresholdLimits limits(Reading reading) {
        return reading.thresholdId() == null ? null : thresholds.findById(reading.thresholdId())
                .map(setting -> setting.limits()).orElse(null);
    }
}
