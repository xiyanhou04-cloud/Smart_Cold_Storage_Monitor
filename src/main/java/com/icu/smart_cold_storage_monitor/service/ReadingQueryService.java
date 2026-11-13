package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.ReadingMetric;
import com.icu.smart_cold_storage_monitor.repository.DeviceRepository;
import com.icu.smart_cold_storage_monitor.repository.ReadingRepository;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Temperature and humidity readings per device over a time range, for the dashboard trend chart (FR-03).
 *
 * <p>Defaults: every registered device, both metrics, and the last {@link #DEFAULT_WINDOW} up to now. The range is
 * {@code [from, to)} and may span at most {@link #MAX_RANGE}. Read-only and deliberately not {@code @Transactional}
 * (a transaction would take SQLite's single write lock).
 */
@Service
public class ReadingQueryService {

    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);
    public static final Duration MAX_RANGE = Duration.ofHours(6);
    public static final int MAX_DEVICES = 50;
    private static final int MAX_DEVICE_ID_LENGTH = 64;

    private final DeviceRepository devices;
    private final ReadingRepository readings;
    private final Clock clock;

    public ReadingQueryService(DeviceRepository devices, ReadingRepository readings, Clock clock) {
        this.devices = devices;
        this.readings = readings;
        this.clock = clock;
    }

    /** One time-ordered value of one metric. */
    public record ReadingPoint(Instant timestamp, double value) {
    }

    /** A device's points per requested metric; a metric that was not requested is {@code null}. */
    public record DeviceReadings(List<ReadingPoint> temperatureC, List<ReadingPoint> humidityPct) {
    }

    /**
     * @param deviceIds devices to include; null or empty means every registered device
     * @param from      inclusive start; must not be combined with {@code window}
     * @param to        exclusive end; defaults to now
     * @param window    length of the range ending at {@code to} when {@code from} is absent; defaults to 5 minutes
     * @param metrics   {@code temperature} and/or {@code humidity}; null or empty means both
     * @return device id to its readings, in device id order, including requested devices without readings
     */
    public Map<String, DeviceReadings> query(List<String> deviceIds, Instant from, Instant to, Duration window,
                                             List<String> metrics) {
        Set<ReadingMetric> selected = parseMetrics(metrics);
        List<String> selectedDevices = selectDevices(deviceIds);

        if (from != null && window != null) {
            throw new InvalidInputException("window", "Use either 'window' or 'from', not both.");
        }
        if (window != null && (window.isNegative() || window.isZero() || window.compareTo(MAX_RANGE) > 0)) {
            throw new InvalidInputException("window", "'window' must be positive and at most " + MAX_RANGE.toHours()
                    + " hours.");
        }
        Instant end = to != null ? to : clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant start = from != null ? from : end.minus(window != null ? window : DEFAULT_WINDOW);
        if (!end.isAfter(start)) {
            throw new InvalidInputException("to", "'to' must be later than 'from'.");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new InvalidInputException("from", "The time range must be at most " + MAX_RANGE.toHours() + " hours.");
        }

        Map<String, List<ReadingPoint>> temperature = new LinkedHashMap<>();
        Map<String, List<ReadingPoint>> humidity = new LinkedHashMap<>();
        for (String device : selectedDevices) {
            temperature.put(device, new ArrayList<>());
            humidity.put(device, new ArrayList<>());
        }
        for (Reading reading : readings.findByDevicesAndTimeRange(selectedDevices, start, end)) {
            temperature.get(reading.deviceId()).add(new ReadingPoint(reading.measuredAt(), reading.temperatureC()));
            humidity.get(reading.deviceId()).add(new ReadingPoint(reading.measuredAt(), reading.humidityPct()));
        }

        Map<String, DeviceReadings> result = new LinkedHashMap<>();
        for (String device : selectedDevices) {
            result.put(device, new DeviceReadings(
                    selected.contains(ReadingMetric.TEMPERATURE) ? List.copyOf(temperature.get(device)) : null,
                    selected.contains(ReadingMetric.HUMIDITY) ? List.copyOf(humidity.get(device)) : null));
        }
        return result;
    }

    private static Set<ReadingMetric> parseMetrics(List<String> metrics) {
        Set<ReadingMetric> selected = EnumSet.noneOf(ReadingMetric.class);
        if (metrics != null) {
            for (String value : metrics) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                selected.add(ReadingMetric.parse(value).orElseThrow(() -> new InvalidInputException("metric",
                        "Unknown metric '" + value.strip() + "'; use temperature and/or humidity.")));
            }
        }
        return selected.isEmpty() ? EnumSet.allOf(ReadingMetric.class) : selected;
    }

    /** The requested devices in id order, or every registered device when none were requested. */
    private List<String> selectDevices(List<String> deviceIds) {
        Set<String> requested = new LinkedHashSet<>();
        if (deviceIds != null) {
            for (String id : deviceIds) {
                if (id != null && !id.isBlank()) {
                    requested.add(id.strip());
                }
            }
        }
        if (requested.size() > MAX_DEVICES) {
            throw new InvalidInputException("deviceId", "At most " + MAX_DEVICES + " device ids per query.");
        }
        if (requested.stream().anyMatch(id -> id.length() > MAX_DEVICE_ID_LENGTH)) {
            throw new InvalidInputException("deviceId",
                    "Device ids are at most " + MAX_DEVICE_ID_LENGTH + " characters.");
        }
        List<String> registered = devices.findAllIds();
        if (requested.isEmpty()) {
            return registered;
        }
        List<String> unknown = requested.stream().filter(id -> !registered.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new InvalidInputException("deviceId", "Unknown device id(s): " + String.join(", ", unknown) + ".");
        }
        return registered.stream().filter(requested::contains).toList();
    }
}
