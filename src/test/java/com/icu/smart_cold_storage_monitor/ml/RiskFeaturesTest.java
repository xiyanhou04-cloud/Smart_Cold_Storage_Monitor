package com.icu.smart_cold_storage_monitor.ml;

import static org.junit.jupiter.api.Assertions.*;

import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RiskFeaturesTest {

    private static final ThresholdLimits A = new ThresholdLimits(0, 4, 55, 80);
    private static final ThresholdLimits B = new ThresholdLimits(0, 6, 55, 80);
    private static final Instant START = Instant.EPOCH;

    @Test
    void usesLeftReadingThresholdAndCountsExactlyThirtySeconds() {
        var features = new RiskFeatures(START, "RAW_MEAT");
        assertEquals(0, features.accept(START, 5, 70, A).observedAboveUpperSeconds());
        var changed = features.accept(START.plusSeconds(30), 5, 70, B);
        assertEquals(30, changed.observedAboveUpperSeconds());
        assertEquals(30, changed.elapsedStorageSeconds());
        assertFalse(changed.incompleteHistory());
        assertEquals(30, features.accept(START.plusSeconds(60), 6, 70, B).observedAboveUpperSeconds());
        assertEquals(30, features.accept(START.plusSeconds(65), -1, 70, B).observedAboveUpperSeconds());
        assertEquals(30, features.accept(START.plusSeconds(70), 6, 70, B).observedAboveUpperSeconds());
    }

    @Test
    void skipsTheEntireGapAndPreservesItsFlag() {
        var features = new RiskFeatures(START, "RAW_MEAT");
        features.accept(START, 5, 70, A);
        var gap = features.accept(START.plusMillis(30_001), 5, 70, A);
        assertTrue(gap.incompleteHistory());
        assertEquals(0, gap.observedAboveUpperSeconds());
        assertEquals(30.001, gap.elapsedStorageSeconds(), 1e-9);
        var next = features.accept(START.plusMillis(31_501), 4, 70, A);
        assertEquals(1.5, next.observedAboveUpperSeconds());
        assertTrue(next.incompleteHistory());
    }

    @Test
    void flagsMissingHistoryAndRejectsInvalidInputWithoutAdvancing() {
        var features = new RiskFeatures(START, "RAW_MEAT");
        assertThrows(IllegalArgumentException.class, () -> features.accept(START.minusSeconds(1), 4, 70, A));
        assertThrows(IllegalArgumentException.class, () -> features.accept(START, Double.NaN, 70, A));
        assertThrows(IllegalArgumentException.class, () -> features.accept(START, 4, 101, A));
        assertTrue(features.accept(START.plusSeconds(31), 5, 70, A).incompleteHistory());
        assertThrows(IllegalArgumentException.class, () -> features.accept(START.plusSeconds(31), 4, 70, A));
        var first = new RiskFeatures(START, "RAW_MEAT").accept(START.plusSeconds(30), 5, 70, A);
        assertFalse(first.incompleteHistory());
        assertEquals(0, first.observedAboveUpperSeconds());
        assertTrue(new RiskFeatures(START, "RAW_MEAT").accept(START, 5, 70, null).incompleteHistory());
    }

    @Test
    void appliesInclusiveCutoffsAndTemperatureBoundsWithGapFloor() {
        var rules = new RiskRules(120, 600);
        assertThrows(IllegalArgumentException.class, () -> new RiskRules(10, 10));
        assertEquals(RiskLevel.LOW, rules.predict(snapshot(4, 0, false), A));
        assertEquals(RiskLevel.LOW, rules.predict(snapshot(0, 119, false), A));
        assertEquals(RiskLevel.MEDIUM, rules.predict(snapshot(-0.1, 0, false), A));
        assertEquals(RiskLevel.MEDIUM, rules.predict(snapshot(4.1, 0, false), A));
        assertEquals(RiskLevel.MEDIUM, rules.predict(snapshot(2, 120, false), A));
        assertEquals(RiskLevel.MEDIUM, rules.predict(snapshot(2, 0, true), A));
        assertEquals(RiskLevel.HIGH, rules.predict(snapshot(2, 600, true), A));
    }

    private static RiskFeatures.Snapshot snapshot(double temperature, double exposure, boolean gap) {
        return new RiskFeatures.Snapshot(temperature, 90, exposure, 1000, "RAW_MEAT", gap);
    }
}
