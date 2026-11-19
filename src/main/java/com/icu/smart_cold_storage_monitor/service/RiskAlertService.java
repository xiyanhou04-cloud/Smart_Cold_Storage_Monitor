package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.ml.RiskFeatures;
import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Estimate;
import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Quality;
import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertResolveReason;
import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository.NewRiskAlert;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Risk alerts (FR-05) aggregated from per-reading predictions, run in the transaction that stores the prediction,
 * so a prediction and its alert transition commit or roll back together.
 *
 * <p>Predictions arrive every few seconds, so one alert stands for a run of predictions: per session (one device)
 * and severity, a MEDIUM or HIGH prediction that comes no later than {@link #window} after the previous prediction
 * of that severity extends the active alert ({@code last_seen_at}); otherwise it opens a new alert. MEDIUM and HIGH
 * are separate alerts and can be active at the same time. An active alert resolves ({@code RECOVERED}, at the
 * measured time of the reading that shows it) once a later prediction of the session comes more than the window
 * after its last one. Predictions that only reflect a gap in the readings ({@link #GAP_QUALITIES}) never open or
 * extend an alert. Times are measured times of the readings.
 */
@Service
public class RiskAlertService {

    /** Qualities of rules predictions made only because readings are missing: they never raise an alert. */
    public static final Set<Quality> GAP_QUALITIES = EnumSet.of(Quality.INCOMPLETE_HISTORY,
            Quality.MONITORING_INTERRUPTED, Quality.MONITORING_INTERRUPTION_UNKNOWN);

    private static final Logger log = LoggerFactory.getLogger(RiskAlertService.class);

    private final AlertRepository alerts;
    private final Duration window;

    public RiskAlertService(AlertRepository alerts, @Value("${app.risk.alert-window:PT5M}") Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("app.risk.alert-window must be positive.");
        }
        this.alerts = alerts;
        this.window = window;
    }

    /**
     * Applies one stored prediction to its session's risk alerts. Must run in the transaction that stored the
     * prediction, and only for a prediction that was actually inserted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluate(Reading reading, Estimate estimate, RiskFeatures.Snapshot features) {
        Instant at = reading.measuredAt();
        RiskLevel level = estimate.level();
        boolean raises = level != RiskLevel.LOW && !GAP_QUALITIES.contains(estimate.quality());
        Alert sameSeverity = null;
        for (Alert alert : alerts.findActiveRiskAlerts(reading.sessionId())) {
            if (Duration.between(alert.lastSeenAt(), at).compareTo(window) > 0) {
                alerts.resolve(alert.id(), at, AlertResolveReason.RECOVERED);
                log.info("Risk alert {} ({}, device {}) resolved: no {} prediction since {}", alert.id(),
                        alert.severity(), alert.deviceId(), alert.severity(), alert.lastSeenAt());
            } else if (raises && alert.severity() == level) {
                sameSeverity = alert;
            }
        }
        if (!raises) {
            return;
        }
        if (sameSeverity == null) {
            Alert opened = alerts.insertRiskAlert(new NewRiskAlert(reading.sessionId(), reading.deviceId(), level,
                    estimate.source(), estimate.version(), estimate.quality().name(), at,
                    describe(estimate, features)));
            log.info("Risk alert {} opened: {} for device {} at {} ({})", opened.id(), level, reading.deviceId(),
                    at, estimate.source());
        } else if (at.isAfter(sameSeverity.lastSeenAt())) {
            alerts.updateRiskEvidence(sameSeverity.id(), at);
        }
    }

    /** The opening prediction's explanation, e.g. {@code The risk model rated the storage risk HIGH: ...}. */
    static String describe(Estimate estimate, RiskFeatures.Snapshot features) {
        String source = estimate.source() == AlertSource.MODEL ? "The risk model"
                : "The rules fallback (" + estimate.quality().name() + ")";
        return String.format(Locale.ROOT,
                "%s rated the storage risk %s: temperature %.1f °C, %.0f s observed above the upper temperature limit.",
                source, estimate.level(), features.temperatureC(), features.observedAboveUpperSeconds());
    }
}
