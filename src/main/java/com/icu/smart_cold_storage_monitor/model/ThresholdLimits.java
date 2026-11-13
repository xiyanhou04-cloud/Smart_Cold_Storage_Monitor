package com.icu.smart_cold_storage_monitor.model;

import java.util.Optional;

/**
 * Temperature (°C) and humidity (%RH) limits of one threshold version. A value equal to a limit is in range.
 *
 * <p>The record itself accepts any values so stored rows can always be read back; {@link #findViolation()} applies
 * the rules for new limits: finite numbers, temperature within the sensor range {@value #MIN_TEMPERATURE_C} to
 * {@value #MAX_TEMPERATURE_C} °C, humidity within 0 to 100 %RH, and each lower limit strictly below its upper limit.
 */
public record ThresholdLimits(double temperatureLowerC, double temperatureUpperC, double humidityLowerPct,
                              double humidityUpperPct) {

    /** SHT45 measurement range, the same plausibility range readings are checked against. */
    public static final double MIN_TEMPERATURE_C = -40;
    public static final double MAX_TEMPERATURE_C = 125;
    public static final double MIN_HUMIDITY_PCT = 0;
    public static final double MAX_HUMIDITY_PCT = 100;

    /** A broken rule: {@code field} is the request field it concerns. */
    public record Violation(String field, String message) {
    }

    /** The first rule these limits break, or empty when they are valid. */
    public Optional<Violation> findViolation() {
        Optional<Violation> violation = range("temperatureLowerC", temperatureLowerC, MIN_TEMPERATURE_C,
                MAX_TEMPERATURE_C)
                .or(() -> range("temperatureUpperC", temperatureUpperC, MIN_TEMPERATURE_C, MAX_TEMPERATURE_C))
                .or(() -> range("humidityLowerPct", humidityLowerPct, MIN_HUMIDITY_PCT, MAX_HUMIDITY_PCT))
                .or(() -> range("humidityUpperPct", humidityUpperPct, MIN_HUMIDITY_PCT, MAX_HUMIDITY_PCT));
        if (violation.isPresent()) {
            return violation;
        }
        if (!(temperatureLowerC < temperatureUpperC)) {
            return Optional.of(new Violation("temperatureUpperC",
                    "Upper temperature limit must be greater than the lower limit."));
        }
        if (!(humidityLowerPct < humidityUpperPct)) {
            return Optional.of(new Violation("humidityUpperPct",
                    "Upper humidity limit must be greater than the lower limit."));
        }
        return Optional.empty();
    }

    /** Compact form for audit details, e.g. {@code T=[0.0, 4.0] C, RH=[55.0, 80.0] %}. */
    public String describe() {
        return "T=[" + temperatureLowerC + ", " + temperatureUpperC + "] C, RH=[" + humidityLowerPct + ", "
                + humidityUpperPct + "] %";
    }

    private static Optional<Violation> range(String field, double value, double min, double max) {
        if (Double.isFinite(value) && value >= min && value <= max) {
            return Optional.empty();
        }
        return Optional.of(new Violation(field, "Must be a number from " + min + " to " + max + "."));
    }
}
