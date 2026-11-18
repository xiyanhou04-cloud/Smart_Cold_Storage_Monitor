package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.util.Arrays;
import java.util.Locale;

/** Confusion counts with explicit reporting for classes absent from the holdout. */
final class RiskMetrics {

    private final int[][] counts = new int[RiskLevel.values().length][RiskLevel.values().length];

    void add(RiskLevel actual, RiskLevel predicted) {
        counts[actual.ordinal()][predicted.ordinal()]++;
    }

    double accuracy() {
        int all = 0;
        int correct = 0;
        for (int i = 0; i < counts.length; i++) {
            all += Arrays.stream(counts[i]).sum();
            correct += counts[i][i];
        }
        return (double) correct / all;
    }

    /** Average over labels with actual support in this evaluation group. */
    double macroF1() {
        double total = 0;
        int classes = 0;
        for (int i = 0; i < counts.length; i++) {
            int support = Arrays.stream(counts[i]).sum();
            if (support > 0) {
                int predicted = 0;
                for (int[] row : counts) {
                    predicted += row[i];
                }
                total += 2.0 * counts[i][i] / (support + predicted);
                classes++;
            }
        }
        return total / classes;
    }

    void appendTo(StringBuilder log) {
        log.append(String.format(Locale.ROOT, "Accuracy: %.6f%n", accuracy()));
        log.append("Confusion matrix (rows actual, columns predicted; LOW, MEDIUM, HIGH):\n");
        for (int[] row : counts) {
            log.append(Arrays.toString(row)).append('\n');
        }
        for (RiskLevel level : RiskLevel.values()) {
            int i = level.ordinal();
            int support = Arrays.stream(counts[i]).sum();
            int predicted = 0;
            for (int[] row : counts) {
                predicted += row[i];
            }
            if (support == 0) {
                log.append(String.format(Locale.ROOT, "%s support=0 predicted=%d precision=N/A recall=N/A F1=N/A%n",
                        level, predicted));
                continue;
            }
            int tp = counts[i][i];
            log.append(String.format(Locale.ROOT, "%s support=%d predicted=%d precision=%s recall=%.6f F1=%.6f%n",
                    level, support, predicted,
                    predicted == 0 ? "N/A" : String.format(Locale.ROOT, "%.6f", (double) tp / predicted),
                    (double) tp / support, 2.0 * tp / (support + predicted)));
        }
    }
}
