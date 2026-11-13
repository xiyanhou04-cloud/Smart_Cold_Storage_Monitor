package com.icu.smart_cold_storage_monitor.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ThresholdLimitsTest {

    @Test
    void validLimitsIncludingTheRangeEdgesHaveNoViolation() {
        assertThat(new ThresholdLimits(0, 4, 55, 80).findViolation()).isEmpty();
        assertThat(new ThresholdLimits(-40, 125, 0, 100).findViolation()).isEmpty();
        assertThat(new ThresholdLimits(-25, -18, 35, 80).findViolation()).isEmpty();
    }

    @Test
    void eachLowerLimitMustBeStrictlyBelowItsUpperLimit() {
        assertThat(field(new ThresholdLimits(4, 4, 55, 80))).contains("temperatureUpperC");
        assertThat(field(new ThresholdLimits(8, 2, 55, 80))).contains("temperatureUpperC");
        assertThat(field(new ThresholdLimits(0, 4, 80, 80))).contains("humidityUpperPct");
        assertThat(field(new ThresholdLimits(0, 4, 90, 50))).contains("humidityUpperPct");
    }

    @Test
    void valuesOutsideTheSensorRangeOrNotFiniteAreRejected() {
        assertThat(field(new ThresholdLimits(-40.1, 4, 55, 80))).contains("temperatureLowerC");
        assertThat(field(new ThresholdLimits(0, 125.1, 55, 80))).contains("temperatureUpperC");
        assertThat(field(new ThresholdLimits(0, 4, -0.1, 80))).contains("humidityLowerPct");
        assertThat(field(new ThresholdLimits(0, 4, 55, 100.1))).contains("humidityUpperPct");
        assertThat(field(new ThresholdLimits(Double.NaN, 4, 55, 80))).contains("temperatureLowerC");
        assertThat(field(new ThresholdLimits(0, Double.POSITIVE_INFINITY, 55, 80))).contains("temperatureUpperC");
    }

    private static Optional<String> field(ThresholdLimits limits) {
        return limits.findViolation().map(ThresholdLimits.Violation::field);
    }
}
