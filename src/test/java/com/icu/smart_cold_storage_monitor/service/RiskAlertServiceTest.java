package com.icu.smart_cold_storage_monitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.ml.RiskFeatures;
import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Estimate;
import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Quality;
import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertResolveReason;
import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.StorageSession;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aggregation of per-reading predictions into risk alerts: one alert per session and severity while predictions of
 * that severity follow each other within the 5-minute window.
 */
class RiskAlertServiceTest extends SqliteIntegrationTest {

    static final String DEVICE = "risk-alert-device";
    static final String OTHER_DEVICE = "risk-alert-other";
    static final String MODEL = "random-forest-ml2-v1";

    @Autowired RiskAlertService riskAlerts;
    @Autowired AlertRepository alerts;
    @Autowired DeviceSessionService devices;
    @Autowired PlatformTransactionManager transactionManager;

    StorageSession session;
    Instant start;
    long nextReadingId = 1;

    @BeforeEach
    void session() {
        session = devices.ensureDeviceWithActiveSession(DEVICE, "test sensor", "RAW-MEAT", "RAW-MEAT");
        start = session.startedAt();
    }

    @Test
    void predictionsOfOneSeverityWithinTheWindowFormOneAlert() {
        for (int second = 0; second <= 600; second += 5) {
            predict(session, second, RiskLevel.MEDIUM);
        }

        Alert alert = single(riskAlerts());
        assertThat(alert.severity()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(alert.source()).isEqualTo(AlertSource.MODEL);
        assertThat(alert.modelOrRuleVersion()).isEqualTo(MODEL);
        assertThat(alert.qualityFlag()).isEqualTo("OK");
        assertThat(alert.openedAt()).isEqualTo(start);
        assertThat(alert.lastSeenAt()).isEqualTo(start.plusSeconds(600));
        assertThat(alert.active()).isTrue();
        assertThat(alert.description())
                .isEqualTo("The risk model rated the storage risk MEDIUM: temperature 4.6 °C, 30 s observed above "
                        + "the upper temperature limit.");
    }

    @Test
    void aSilenceOfExactlyFiveMinutesKeepsTheAlertAndALongerOneStartsANewAlert() {
        predict(session, 0, RiskLevel.MEDIUM);
        predict(session, 300, RiskLevel.MEDIUM);
        assertThat(single(riskAlerts()).lastSeenAt()).isEqualTo(start.plusSeconds(300));

        predict(session, 601, RiskLevel.MEDIUM);

        List<Alert> all = riskAlerts();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).resolvedAt()).isEqualTo(start.plusSeconds(601));
        assertThat(all.get(0).resolveReason()).isEqualTo(AlertResolveReason.RECOVERED);
        assertThat(all.get(0).lastSeenAt()).isEqualTo(start.plusSeconds(300));
        assertThat(all.get(1).openedAt()).isEqualTo(start.plusSeconds(601));
        assertThat(all.get(1).active()).isTrue();
    }

    @Test
    void lowPredictionsInsideTheWindowDoNotSplitTheAlert() {
        predict(session, 0, RiskLevel.MEDIUM);
        for (int second = 5; second < 240; second += 5) {
            predict(session, second, RiskLevel.LOW);
        }
        predict(session, 240, RiskLevel.MEDIUM);

        Alert alert = single(riskAlerts());
        assertThat(alert.active()).isTrue();
        assertThat(alert.lastSeenAt()).isEqualTo(start.plusSeconds(240));
    }

    @Test
    void theAlertResolvesAtTheFirstPredictionAfterTheWindowWhateverItsLevel() {
        predict(session, 0, RiskLevel.MEDIUM);
        predict(session, 295, RiskLevel.LOW);
        assertThat(single(riskAlerts()).active()).isTrue();

        predict(session, 305, RiskLevel.LOW);

        Alert alert = single(riskAlerts());
        assertThat(alert.resolvedAt()).isEqualTo(start.plusSeconds(305));
        assertThat(alert.resolveReason()).isEqualTo(AlertResolveReason.RECOVERED);
    }

    @Test
    void mediumAndHighAreSeparateAlertsThatCanBeActiveTogether() {
        predict(session, 0, RiskLevel.MEDIUM);
        predict(session, 5, RiskLevel.HIGH);
        predict(session, 10, RiskLevel.MEDIUM);
        predict(session, 15, RiskLevel.HIGH);
        predict(session, 200, RiskLevel.HIGH);

        List<Alert> all = riskAlerts();
        assertThat(all).extracting(Alert::severity).containsExactly(RiskLevel.MEDIUM, RiskLevel.HIGH);
        assertThat(all).allMatch(Alert::active);
        assertThat(all.get(0).lastSeenAt()).isEqualTo(start.plusSeconds(10));
        assertThat(all.get(1).lastSeenAt()).isEqualTo(start.plusSeconds(200));

        predict(session, 400, RiskLevel.HIGH);

        all = riskAlerts();
        assertThat(all.get(0).resolvedAt()).isEqualTo(start.plusSeconds(400));
        assertThat(all.get(1).active()).isTrue();
    }

    @Test
    void lowPredictionsNeverOpenAnAlert() {
        for (int second = 0; second <= 60; second += 5) {
            predict(session, second, RiskLevel.LOW);
        }

        assertThat(riskAlerts()).isEmpty();
    }

    @Test
    void gapFallbacksNeitherOpenNorExtendAnAlertButCanEndOne() {
        evaluate(session, 0, new Estimate(RiskLevel.MEDIUM, AlertSource.RULES, "simulated-exposure-v1",
                Quality.MONITORING_INTERRUPTED));
        assertThat(riskAlerts()).isEmpty();

        predict(session, 5, RiskLevel.MEDIUM);
        evaluate(session, 200, new Estimate(RiskLevel.MEDIUM, AlertSource.RULES, "simulated-exposure-v1",
                Quality.INCOMPLETE_HISTORY));
        assertThat(single(riskAlerts()).lastSeenAt()).isEqualTo(start.plusSeconds(5));

        evaluate(session, 400, new Estimate(RiskLevel.HIGH, AlertSource.RULES, "simulated-exposure-v1",
                Quality.MONITORING_INTERRUPTION_UNKNOWN));
        Alert alert = single(riskAlerts());
        assertThat(alert.resolvedAt()).isEqualTo(start.plusSeconds(400));
    }

    @Test
    void aRulesFallbackForAnUnavailableModelRaisesAnAlert() {
        evaluate(session, 0, new Estimate(RiskLevel.HIGH, AlertSource.RULES, "simulated-exposure-v1",
                Quality.MODEL_MISSING));

        Alert alert = single(riskAlerts());
        assertThat(alert.severity()).isEqualTo(RiskLevel.HIGH);
        assertThat(alert.source()).isEqualTo(AlertSource.RULES);
        assertThat(alert.qualityFlag()).isEqualTo("MODEL_MISSING");
        assertThat(alert.description()).startsWith("The rules fallback (MODEL_MISSING) rated the storage risk HIGH");
    }

    @Test
    void anOlderPredictionProcessedLateNeverMovesTheAlertBack() {
        predict(session, 0, RiskLevel.MEDIUM);
        predict(session, 60, RiskLevel.MEDIUM);
        predict(session, 30, RiskLevel.MEDIUM);

        Alert alert = single(riskAlerts());
        assertThat(alert.openedAt()).isEqualTo(start);
        assertThat(alert.lastSeenAt()).isEqualTo(start.plusSeconds(60));
    }

    @Test
    void eachDeviceSessionHasItsOwnAlerts() {
        var other = devices.ensureDeviceWithActiveSession(OTHER_DEVICE, "test sensor", "EGGS", "EGGS");
        predict(session, 0, RiskLevel.MEDIUM);
        evaluate(other, 10, estimate(RiskLevel.MEDIUM));

        assertThat(alerts.findActiveRiskAlerts(session.id())).hasSize(1);
        assertThat(alerts.findActiveRiskAlerts(other.id())).hasSize(1);
    }

    @Test
    void mustRunInsideTheTransactionThatStoresThePrediction() {
        assertThatThrownBy(() -> riskAlerts.evaluate(reading(session, 0), estimate(RiskLevel.MEDIUM), features()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(riskAlerts()).isEmpty();
    }

    private void predict(StorageSession target, long second, RiskLevel level) {
        evaluate(target, second, estimate(level));
    }

    private void evaluate(StorageSession target, long second, Estimate estimate) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                riskAlerts.evaluate(reading(target, second), estimate, features()));
    }

    private Reading reading(StorageSession target, long second) {
        return new Reading(nextReadingId++, target.deviceId(), target.id(), start.plusSeconds(second),
                start.plusSeconds(second), 4.6, 70, null);
    }

    private static Estimate estimate(RiskLevel level) {
        return new Estimate(level, AlertSource.MODEL, MODEL, Quality.OK);
    }

    private static RiskFeatures.Snapshot features() {
        return new RiskFeatures.Snapshot(4.6, 70, 30, 300, "RAW_MEAT", false);
    }

    private List<Alert> riskAlerts() {
        return alerts.findBySession(session.id()).stream().filter(alert -> alert.kind().name().equals("RISK")).toList();
    }

    private static Alert single(List<Alert> list) {
        assertThat(list).hasSize(1);
        return list.getFirst();
    }
}
