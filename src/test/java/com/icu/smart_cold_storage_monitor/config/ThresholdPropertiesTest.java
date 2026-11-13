package com.icu.smart_cold_storage_monitor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ThresholdPropertiesTest {

    static final ThresholdLimits RAW_MEAT = new ThresholdLimits(0, 4, 55, 80);
    static final ThresholdLimits FALLBACK = new ThresholdLimits(1, 7, 40, 90);

    @Test
    void aListedLocationGetsItsLimitsAndAnyOtherGetsTheFallback() {
        ThresholdProperties properties = new ThresholdProperties(FALLBACK, Map.of("RAW-MEAT", RAW_MEAT));

        assertThat(properties.defaultFor("RAW-MEAT")).isEqualTo(RAW_MEAT);
        assertThat(properties.defaultFor("UNKNOWN")).isEqualTo(FALLBACK);
        assertThat(properties.defaultFor(null)).isEqualTo(FALLBACK);
        assertThat(properties.defaultFor("raw-meat")).as("location codes match exactly").isEqualTo(FALLBACK);
    }

    @Test
    void missingConfigurationUsesTheBuiltInFallback() {
        ThresholdProperties properties = new ThresholdProperties(null, null);

        assertThat(properties.defaultFor("RAW-MEAT")).isEqualTo(ThresholdProperties.BUILT_IN_FALLBACK);
    }

    @Test
    void invalidLimitsAreRejectedWithThePropertyName() {
        assertThatThrownBy(() -> new ThresholdProperties(new ThresholdLimits(4, 0, 55, 80), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.thresholds.fallback.temperatureUpperC");
        assertThatThrownBy(() -> new ThresholdProperties(FALLBACK,
                Map.of("FROZEN", new ThresholdLimits(-25, -18, 35, 101))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.thresholds.locations[FROZEN].humidityUpperPct");
    }

    @Test
    void bracketedLocationKeysAreBoundAsWritten() {
        var source = new MapConfigurationPropertySource(Map.of(
                "app.thresholds.fallback.temperature-lower-c", "1",
                "app.thresholds.fallback.temperature-upper-c", "7",
                "app.thresholds.fallback.humidity-lower-pct", "40",
                "app.thresholds.fallback.humidity-upper-pct", "90",
                "app.thresholds.locations[FRUIT-COLD-BERRY].temperature-lower-c", "-0.5",
                "app.thresholds.locations[FRUIT-COLD-BERRY].temperature-upper-c", "0.5",
                "app.thresholds.locations[FRUIT-COLD-BERRY].humidity-lower-pct", "90",
                "app.thresholds.locations[FRUIT-COLD-BERRY].humidity-upper-pct", "95"));

        ThresholdProperties properties = new Binder(source).bind("app.thresholds", ThresholdProperties.class).get();

        assertThat(properties.defaultFor("FRUIT-COLD-BERRY")).isEqualTo(new ThresholdLimits(-0.5, 0.5, 90, 95));
        assertThat(properties.defaultFor(null)).isEqualTo(FALLBACK);
    }
}
