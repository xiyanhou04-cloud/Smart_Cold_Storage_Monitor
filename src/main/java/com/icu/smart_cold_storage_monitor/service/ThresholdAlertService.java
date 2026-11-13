package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertDirection;
import com.icu.smart_cold_storage_monitor.model.AlertResolveReason;
import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.ReadingMetric;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.model.ThresholdSetting;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository.NewThresholdAlert;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Threshold alert state machine (FR-04), run for every accepted reading inside the reading's transaction, so a
 * reading and its alert transition commit or roll back together.
 *
 * <p>Temperature and humidity are checked independently against the threshold version the reading recorded. A
 * value equal to a limit is in range. Per metric: out of range with no active alert opens one; further out-of-range
 * readings in the same direction update it ({@code last_seen_at}, last and peak value); the next in-range reading
 * resolves it ({@code RECOVERED}); a jump straight across the range (below the lower to above the upper limit, or
 * back) resolves it as {@code SUPERSEDED} and opens a new alert for the new direction. Every threshold alert has
 * severity {@link #SEVERITY}: an out-of-range value counts as high risk.
 */
@Service
public class ThresholdAlertService {

    /** Severity of every threshold alert. */
    public static final RiskLevel SEVERITY = RiskLevel.HIGH;

    private static final Logger log = LoggerFactory.getLogger(ThresholdAlertService.class);

    private final AlertRepository alerts;

    public ThresholdAlertService(AlertRepository alerts) {
        this.alerts = alerts;
    }

    /**
     * Applies one accepted reading to its session's threshold alerts. Must run in the transaction that stored the
     * reading.
     *
     * @param threshold the version the reading recorded ({@code reading.thresholdId()})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluate(Reading reading, ThresholdSetting threshold) {
        if (reading.thresholdId() == null || reading.thresholdId() != threshold.id()) {
            throw new IllegalArgumentException("The threshold version must be the one the reading recorded.");
        }
        Map<ReadingMetric, Alert> active = new EnumMap<>(ReadingMetric.class);
        for (Alert alert : alerts.findActiveThresholdAlerts(reading.sessionId())) {
            active.put(alert.metric(), alert);
        }
        ThresholdLimits limits = threshold.limits();
        evaluate(reading, threshold, ReadingMetric.TEMPERATURE, reading.temperatureC(),
                limits.temperatureLowerC(), limits.temperatureUpperC(), active.get(ReadingMetric.TEMPERATURE));
        evaluate(reading, threshold, ReadingMetric.HUMIDITY, reading.humidityPct(),
                limits.humidityLowerPct(), limits.humidityUpperPct(), active.get(ReadingMetric.HUMIDITY));
    }

    private void evaluate(Reading reading, ThresholdSetting threshold, ReadingMetric metric, double value,
                          double lower, double upper, Alert active) {
        AlertDirection direction = value < lower ? AlertDirection.BELOW
                : value > upper ? AlertDirection.ABOVE
                : null;
        if (active != null && direction == active.direction()) {
            double peak = direction == AlertDirection.ABOVE ? Math.max(active.peakValue(), value)
                    : Math.min(active.peakValue(), value);
            alerts.updateThresholdEvidence(active.id(), reading.measuredAt(), value, peak);
            return;
        }
        if (active != null) {
            AlertResolveReason reason = direction == null ? AlertResolveReason.RECOVERED : AlertResolveReason.SUPERSEDED;
            alerts.resolve(active.id(), reading.measuredAt(), reason);
            log.info("Alert {} resolved ({}): {} {}, now {} on device {}", active.id(), reason, metric,
                    active.direction(), value, reading.deviceId());
        }
        if (direction != null) {
            double limit = direction == AlertDirection.ABOVE ? upper : lower;
            Alert opened = alerts.insertThresholdAlert(new NewThresholdAlert(reading.sessionId(), reading.deviceId(),
                    SEVERITY, metric, direction, threshold.id(), limit, value, reading.measuredAt()));
            log.info("Alert {} opened: {} {} limit {} ({}) on device {}", opened.id(), metric, direction, limit, value,
                    reading.deviceId());
        }
    }
}
