package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.ExpandedRiskData.Scene;
import com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerator.Reading;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Evaluates each record and retains equal-weight scene summaries. */
final class ExpandedRiskEvaluation {

    @FunctionalInterface
    interface Predictor {
        RiskLevel predict(Scene scene, Reading row) throws Exception;
    }

    record ScenarioScore(String scenarioId, int records, double accuracy, double macroF1) {}
    record Result(RiskMetrics pooled, List<ScenarioScore> scenarios) {
        double meanAccuracy() {
            return scenarios.stream().mapToDouble(ScenarioScore::accuracy).average().orElseThrow();
        }

        double meanMacroF1() {
            return scenarios.stream().mapToDouble(ScenarioScore::macroF1).average().orElseThrow();
        }

        void appendTo(StringBuilder log) {
            pooled.appendTo(log);
            log.append(String.format(Locale.ROOT, "Macro-F1: %.6f%n", pooled.macroF1()));
            log.append(String.format(Locale.ROOT, "Scene mean (%d scenes): Accuracy %.6f; Macro-F1 %.6f%n",
                    scenarios.size(), meanAccuracy(), meanMacroF1()));
        }
    }

    static Result evaluate(List<Scene> scenes, boolean completeOnly, Predictor predictor) throws Exception {
        var pooled = new RiskMetrics();
        var scores = new ArrayList<ScenarioScore>();
        for (Scene scene : scenes) {
            var local = new RiskMetrics();
            int count = 0;
            for (Reading row : scene.rows()) {
                if (completeOnly && row.incompleteHistory()) {
                    continue;
                }
                RiskLevel predicted = predictor.predict(scene, row);
                pooled.add(row.riskLevel(), predicted);
                local.add(row.riskLevel(), predicted);
                count++;
            }
            if (count > 0) {
                scores.add(new ScenarioScore(scene.definition().scenarioId(), count, local.accuracy(), local.macroF1()));
            }
        }
        return new Result(pooled, List.copyOf(scores));
    }

    /** Benchmarks after the scoring pass. Includes feature encoding, excludes feature aggregation and I/O. */
    static double latencyP95(List<Scene> scenes, Predictor predictor) throws Exception {
        int size = scenes.stream().mapToInt(scene -> scene.rows().size()).sum();
        long[] nanoseconds = new long[size * 5];
        int i = 0;
        for (int repeat = 0; repeat < 5; repeat++) {
            for (Scene scene : scenes) {
                for (Reading row : scene.rows()) {
                    long start = System.nanoTime();
                    predictor.predict(scene, row);
                    nanoseconds[i++] = System.nanoTime() - start;
                }
            }
        }
        Arrays.sort(nanoseconds);
        return nanoseconds[(int) Math.ceil(i * 0.95) - 1] / 1000.0;
    }
}
