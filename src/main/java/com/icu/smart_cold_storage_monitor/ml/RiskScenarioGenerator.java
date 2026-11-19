package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builds independently seeded, bounded temperature and humidity traces. */
final class RiskScenarioGenerator {

    static final String VERSION = "cold-storage-scenarios-v1";
    static final String SOURCE = "Artificial scenario; temperature and humidity limits from application.yaml";
    static final List<Mode> SCENARIO_PLAN = List.of(Mode.NORMAL, Mode.SHORT_EXCURSION,
            Mode.SUSTAINED_EXCURSION, Mode.GRADUAL_WARMING, Mode.RECOVERY,
            Mode.REPEATED_EXCURSIONS, Mode.DATA_GAP, Mode.SUSTAINED_EXCURSION,
            Mode.GRADUAL_WARMING, Mode.REPEATED_EXCURSIONS);

    enum Mode {
        NORMAL, SHORT_EXCURSION, SUSTAINED_EXCURSION, GRADUAL_WARMING,
        RECOVERY, REPEATED_EXCURSIONS, DATA_GAP
    }

    record Knot(int seconds, double temperatureC) {}

    record Parameters(int durationSeconds, int cadenceSeconds, int gapStartSeconds, int gapEndSeconds,
                      double normalTemperatureC, double humidityCenterPct, double temperatureNoiseC,
                      double humidityNoisePct, List<Knot> temperatureKnots) {

        boolean omitted(int seconds) {
            return gapStartSeconds >= 0 && seconds >= gapStartSeconds && seconds < gapEndSeconds;
        }
    }

    record Scenario(String scenarioId, long randomSeed, String region, String productType,
                    Instant storageSessionStart, Mode scenarioType, ThresholdLimits thresholds,
                    String generatorVersion, String labelRuleVersion, RiskRules labelCutoffs,
                    String source, Parameters parameters) {}

    record Reading(String scenarioId, long randomSeed, String region, String productType,
                   Instant storageSessionStart, Instant timestamp, double temperatureC, double humidityPct,
                   double thresholdLowerC, double thresholdUpperC, double observedAboveUpperSeconds,
                   double elapsedStorageSeconds, boolean gapFromPrevious, boolean incompleteHistory,
                   Mode scenarioType, String labelRuleVersion, RiskLevel riskLevel) {}

    static Scenario plan(String id, long seed, String region, Instant start, Mode mode, ThresholdLimits limits,
                         int cadence, RiskRules rules) {
        Random random = new Random(seed);
        int duration = ((2100 + random.nextInt(1501)) / cadence) * cadence;
        int onset = 180 + random.nextInt(181);
        double span = limits.temperatureUpperC() - limits.temperatureLowerC();
        double normal = limits.temperatureLowerC() + span * (0.40 + random.nextDouble() * 0.18);
        double warm = limits.temperatureUpperC() + Math.max(0.35, Math.min(3.5, span * 0.65))
                * (0.85 + random.nextDouble() * 0.30);
        double humiditySpan = limits.humidityUpperPct() - limits.humidityLowerPct();
        double humidity = limits.humidityLowerPct() + humiditySpan * (0.44 + random.nextDouble() * 0.12);
        int gapStart = -1;
        int gapEnd = -1;
        List<Knot> knots = new ArrayList<>();
        knots.add(new Knot(0, normal));
        if (mode != Mode.NORMAL) {
            knots.add(new Knot(onset, normal));
        }
        switch (mode) {
            case NORMAL -> { }
            case SHORT_EXCURSION -> episode(knots, onset, 30, 35 + random.nextInt(16), 45, warm, normal);
            case SUSTAINED_EXCURSION ->
                    episode(knots, onset, 60 + random.nextInt(61), 780 + random.nextInt(241), 240, warm, normal);
            case GRADUAL_WARMING ->
                    episode(knots, onset, 480 + random.nextInt(241), 660 + random.nextInt(241), 180, warm, normal);
            case RECOVERY -> episode(knots, onset, 90, 210 + random.nextInt(121),
                    420 + random.nextInt(181), warm, normal);
            case REPEATED_EXCURSIONS -> {
                int next = onset;
                for (int cycle = 0; cycle < 3; cycle++) {
                    if (cycle > 0) {
                        knots.add(new Knot(next, normal));
                    }
                    double peak = limits.temperatureUpperC()
                            + (warm - limits.temperatureUpperC()) * (0.82 + random.nextDouble() * 0.30);
                    episode(knots, next, 45, 220 + random.nextInt(51), 100, peak, normal);
                    next = knots.getLast().seconds() + 100 + random.nextInt(51);
                }
            }
            case DATA_GAP -> {
                episode(knots, onset, 60, 155, 100, warm, normal);
                gapStart = onset + 90;
                gapEnd = gapStart + 65 + random.nextInt(26);
            }
        }
        knots.add(new Knot(duration, normal));
        var parameters = new Parameters(duration, cadence, gapStart, gapEnd, normal, humidity,
                Math.min(0.16, span * 0.12), Math.min(1.2, humiditySpan * 0.14), List.copyOf(knots));
        return new Scenario(id, seed, region, RiskTrainingData.REGION_PRODUCTS.get(region), start, mode,
                limits, VERSION, RiskRules.VERSION, rules, SOURCE, parameters);
    }

    private static void episode(List<Knot> knots, int start, int rise, int hold, int recovery,
                                double peak, double normal) {
        knots.add(new Knot(start + rise, peak));
        knots.add(new Knot(start + rise + hold, peak));
        knots.add(new Knot(start + rise + hold + recovery, normal));
    }

    static List<Reading> generate(Scenario scenario) {
        Parameters p = scenario.parameters();
        ThresholdLimits limits = scenario.thresholds();
        Random noise = new Random(scenario.randomSeed() ^ 0x57A4_1182_792B_600DL);
        RiskFeatures features = new RiskFeatures(scenario.storageSessionStart(), scenario.productType());
        List<Reading> readings = new ArrayList<>();
        int previous = -1;
        double temperatureNoise = 0;
        double humidityNoise = 0;
        for (int seconds = 0; seconds <= p.durationSeconds(); seconds += p.cadenceSeconds()) {
            temperatureNoise = 0.82 * temperatureNoise + 0.18 * jitter(noise, p.temperatureNoiseC());
            humidityNoise = 0.88 * humidityNoise + 0.12 * jitter(noise, p.humidityNoisePct());
            if (p.omitted(seconds)) {
                continue;
            }
            double temperature = round(temperatureAt(p.temperatureKnots(), seconds) + temperatureNoise);
            double humidity = round(p.humidityCenterPct() + humidityNoise
                    - Math.min(p.humidityNoisePct() * 0.5, Math.max(0, temperature - p.normalTemperatureC()) * 0.15));
            Instant time = scenario.storageSessionStart().plusSeconds(seconds);
            var snapshot = features.accept(time, temperature, humidity, limits);
            boolean gap = previous >= 0 && seconds - previous > RiskFeatures.MAX_OBSERVED_INTERVAL.toSeconds();
            readings.add(new Reading(scenario.scenarioId(), scenario.randomSeed(), scenario.region(),
                    scenario.productType(), scenario.storageSessionStart(), time, temperature, humidity,
                    limits.temperatureLowerC(), limits.temperatureUpperC(), snapshot.observedAboveUpperSeconds(),
                    snapshot.elapsedStorageSeconds(), gap, snapshot.incompleteHistory(), scenario.scenarioType(),
                    scenario.labelRuleVersion(), scenario.labelCutoffs().predict(snapshot, limits)));
            previous = seconds;
        }
        return List.copyOf(readings);
    }

    static double temperatureAt(List<Knot> knots, int seconds) {
        for (int i = 1; i < knots.size(); i++) {
            Knot right = knots.get(i);
            if (seconds <= right.seconds()) {
                Knot left = knots.get(i - 1);
                double fraction = (double) (seconds - left.seconds()) / (right.seconds() - left.seconds());
                double smooth = fraction * fraction * (3 - 2 * fraction);
                return left.temperatureC() + (right.temperatureC() - left.temperatureC()) * smooth;
            }
        }
        throw new IllegalArgumentException("Time is outside the planned trace.");
    }

    private static double jitter(Random random, double amplitude) {
        return (2 * random.nextDouble() - 1) * amplitude;
    }

    private static double round(double value) {
        return Math.round(value * 10_000) / 10_000.0;
    }
}
