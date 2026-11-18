package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Reading;
import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Scenario;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.util.ArrayList;
import java.util.List;

/** Verifies the published trace against its schedule and an independent interval tally. */
final class RiskScenarioQuality {

    record Stats(int records, int missingTicks, int gapIntervals, int aboveUpperEpisodes,
                 long observedAboveUpperSeconds, List<Double> temperatureTrace) {}

    static Stats validate(Scenario scenario, List<Reading> readings) {
        var p = scenario.parameters();
        var limits = scenario.thresholds();
        long exposure = 0;
        boolean incomplete = false;
        int missing = 0;
        int gaps = 0;
        int episodes = 0;
        int count = 0;
        Reading previous = null;
        List<Double> temperatureTrace = new ArrayList<>();
        for (int seconds = 0; seconds <= p.durationSeconds(); seconds += p.cadenceSeconds()) {
            if (p.omitted(seconds)) {
                missing++;
                continue;
            }
            require(count < readings.size(), scenario, "missing scheduled sample");
            Reading row = readings.get(count++);
            require(row.timestamp().equals(scenario.storageSessionStart().plusSeconds(seconds)),
                    scenario, "unexpected timestamp, duplicate or out-of-order row");
            double expectedTemperature = RiskScenarioGenerator.temperatureAt(p.temperatureKnots(), seconds);
            require(Math.abs(row.temperatureC() - expectedTemperature) <= p.temperatureNoiseC() + 0.00005,
                    scenario, "temperature left the planned profile envelope");
            require(row.humidityPct() >= limits.humidityLowerPct() && row.humidityPct() <= limits.humidityUpperPct()
                            && Math.abs(row.humidityPct() - p.humidityCenterPct()) <= p.humidityNoisePct() * 1.5 + 0.00005,
                    scenario, "humidity left the operating or noise envelope");
            require(row.temperatureC() >= limits.temperatureLowerC(), scenario, "unexpected low-temperature event");
            boolean previousAbove = previous != null && previous.temperatureC() > previous.thresholdUpperC();
            boolean above = row.temperatureC() > limits.temperatureUpperC();
            if (above && !previousAbove) {
                episodes++;
            }
            boolean gap = false;
            if (previous != null) {
                long delta = row.timestamp().getEpochSecond() - previous.timestamp().getEpochSecond();
                gap = delta > RiskFeatures.MAX_OBSERVED_INTERVAL.toSeconds();
                if (gap) {
                    gaps++;
                } else if (previousAbove) {
                    exposure += delta;
                }
            }
            incomplete |= gap;
            require(row.gapFromPrevious() == gap && row.incompleteHistory() == incomplete
                            && row.observedAboveUpperSeconds() == exposure && row.elapsedStorageSeconds() == seconds,
                    scenario, "incorrect gap, elapsed duration or last-observation-hold exposure");

            RiskLevel expected = exposure >= scenario.labelCutoffs().highAfterSeconds() ? RiskLevel.HIGH
                    : exposure >= scenario.labelCutoffs().mediumAfterSeconds() || above || incomplete
                            ? RiskLevel.MEDIUM : RiskLevel.LOW;
            require(row.riskLevel() == expected, scenario, "incorrect risk label");
            temperatureTrace.add(row.temperatureC());
            previous = row;
        }
        require(count == readings.size(), scenario, "extra rows");
        int expectedEpisodes = switch (scenario.scenarioType()) {
            case NORMAL -> 0;
            case REPEATED_EXCURSIONS -> 3;
            default -> 1;
        };
        require(episodes == expectedEpisodes, scenario, "unexpected excursion count");
        boolean expectedGap = scenario.scenarioType() == RiskScenarioGenerator.Mode.DATA_GAP;
        require(gaps == (expectedGap ? 1 : 0), scenario, "unexpected gap count");
        require(readings.getFirst().temperatureC() <= limits.temperatureUpperC()
                        && readings.getLast().temperatureC() <= limits.temperatureUpperC(),
                scenario, "trace must start and end within range");
        return new Stats(count, missing, gaps, episodes, exposure, List.copyOf(temperatureTrace));
    }

    private static void require(boolean condition, Scenario scenario, String message) {
        if (!condition) {
            throw new IllegalStateException(scenario.scenarioId() + ": " + message);
        }
    }
}
