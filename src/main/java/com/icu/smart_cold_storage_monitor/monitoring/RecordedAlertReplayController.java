package com.icu.smart_cold_storage_monitor.monitoring;

import com.icu.smart_cold_storage_monitor.monitoring.LiveTelemetryController.Channel;
import com.icu.smart_cold_storage_monitor.monitoring.LiveTelemetryService.Observation;
import com.icu.smart_cold_storage_monitor.monitoring.LiveTelemetryService.Zone;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Drives the live zone evaluator with the recorded clock in an isolated replay database. */
@RestController
@Profile("monitoring-live")
class RecordedAlertReplayController {
    private static final long TICK_MS = 5000;
    private final MonitoringProperties properties;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();

    RecordedAlertReplayController(MonitoringProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @GetMapping("/api/monitoring/recorded-alert-replay")
    void replay(@RequestParam double speed, HttpServletResponse response) throws IOException, SQLException {
        if (!Double.isFinite(speed) || speed < 0.1 || speed > 1000) {
            throw new InvalidInputException("speed", "Enter a speed from 0.1 to 1000.");
        }
        if (!running.compareAndSet(false, true)) {
            response.setStatus(429);
            return;
        }
        try {
            List<Zone> zones = LiveTelemetryService.loadZones(properties);
            NavigableMap<Long, Map<String, RecordedSample>> samples = load(zones);
            long first = samples.firstKey();
            long last = samples.lastKey();
            // Preserve recorded instants and also check silent channels on the regular cadence.
            SortedSet<Long> ticks = new TreeSet<>(samples.keySet());
            for (long time = first; time < last; time += TICK_MS) ticks.add(time);
            try (Connection database = DriverManager.getConnection("jdbc:sqlite::memory:");
                 Statement schema = database.createStatement()) {
                LiveTelemetryService.createSchema(schema);
                response.setContentType("application/x-ndjson");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setHeader("Cache-Control", "no-store");
                PrintWriter output = response.getWriter();
                run(database, zones, samples, ticks, speed, output);
            }
        } finally {
            running.set(false);
        }
    }

    private void run(Connection database, List<Zone> zones,
                     NavigableMap<Long, Map<String, RecordedSample>> samples, SortedSet<Long> ticks,
                     double speed, PrintWriter output) throws SQLException {
        long first = samples.firstKey();
        long start = System.nanoTime();
        long eventCursor = 0;
        int processed = 0;
        int tickNumber = 0;
        Map<String, RecordedSample> latest = new HashMap<>();
        for (long time : ticks) {
            long delay = (long) ((time - first) * 1_000_000.0 / speed) - (System.nanoTime() - start);
            try {
                if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            Map<String, RecordedSample> arrivals = samples.getOrDefault(time, Map.of());
            latest.putAll(arrivals);
            processed += arrivals.size();
            List<ZoneReading> readings = new ArrayList<>();
            for (Zone zone : zones) {
                RecordedSample current = latest.get(zone.device());
                boolean fresh = current != null && time - current.time() <= LiveTelemetryService.FRESHNESS_MS;
                Observation temperature = fresh ? current.temperature() : null;
                Observation humidity = fresh ? current.humidity() : null;
                LiveTelemetryService.check(database, zone, "TEMPERATURE", zone.temperature(), temperature, null, time);
                LiveTelemetryService.check(database, zone, "HUMIDITY", zone.humidity(), humidity, null, time);
                readings.add(new ZoneReading(zone.code(), temperature, humidity));
            }
            List<Transition> transitions = transitions(database, eventCursor);
            if (!transitions.isEmpty()) eventCursor = transitions.getLast().id();
            output.println(mapper.writeValueAsString(new ReplayTick("TICK", ++tickNumber, Instant.ofEpochMilli(time),
                arrivals.size(), processed, readings, openAlerts(database), transitions)));
            if (output.checkError()) return;
        }
        output.println(mapper.writeValueAsString(new ReplayComplete("COMPLETE", tickNumber, processed,
            Instant.ofEpochMilli(first), Instant.ofEpochMilli(samples.lastKey()))));
        output.flush();
    }

    private NavigableMap<Long, Map<String, RecordedSample>> load(List<Zone> zones) throws IOException {
        Set<String> devices = new HashSet<>();
        for (Zone zone : zones) devices.add(zone.device());
        byte[] bytes;
        try (var input = Files.newInputStream(properties.getSensorPath())) {
            bytes = input.readNBytes(Math.toIntExact(properties.getMaxSensorFileBytes() + 1));
        }
        if (bytes.length > properties.getMaxSensorFileBytes()) throw invalid("Recording exceeds the configured size limit.");
        NavigableMap<Long, Map<String, RecordedSample>> samples = new TreeMap<>();
        int lineNumber = 0;
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            lineNumber++;
            if (line.isBlank()) continue;
            try {
                JsonNode row = mapper.readTree(line);
                String device = row.get("deviceId").asString();
                if (!devices.contains(device)) throw new IllegalArgumentException("Unmapped device.");
                long time = Instant.parse(row.get("measured_at").asString()).toEpochMilli();
                JsonNode raw = row.get("frame_hex");
                byte[] frame = LiveTelemetryService.parseFrame(raw == null || raw.isNull() ? null : raw.asString());
                RecordedSample sample = new RecordedSample(time, channel(row.get("temperatureC"), frame, true),
                    channel(row.get("humidityPct"), frame, false));
                if (samples.computeIfAbsent(time, key -> new HashMap<>()).putIfAbsent(device, sample) != null) {
                    throw new IllegalArgumentException("Duplicate device timestamp.");
                }
            } catch (RuntimeException ex) {
                throw invalid("Invalid recording identity or timestamp on line " + lineNumber + ".");
            }
        }
        if (samples.isEmpty()) throw invalid("Recording contains no samples.");
        if (samples.lastKey() - samples.firstKey() > TimeUnit.DAYS.toMillis(1)) {
            throw invalid("Recording spans more than one day.");
        }
        return samples;
    }

    private static Observation channel(JsonNode value, byte[] frame, boolean temperature) {
        Channel input = null;
        if (value != null && !value.isNull()) {
            input = value.isNumber() ? new Channel(value.asDouble(), "PRESENT") : new Channel(null, "INVALID");
        }
        return LiveTelemetryService.observation(input, frame, temperature);
    }

    private static InvalidInputException invalid(String message) {
        return new InvalidInputException("recording", message);
    }

    private static List<ReplayAlert> openAlerts(Connection database) throws SQLException {
        List<ReplayAlert> result = new ArrayList<>();
        try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("""
            SELECT id,location,type,opened_at,evidence FROM alerts WHERE closed_at IS NULL ORDER BY location,type
            """)) {
            while (rows.next()) result.add(new ReplayAlert(rows.getLong(1), rows.getString(2), rows.getString(3),
                Instant.ofEpochMilli(rows.getLong(4)), rows.getString(5)));
        }
        return result;
    }

    private static List<Transition> transitions(Connection database, long cursor) throws SQLException {
        List<Transition> result = new ArrayList<>();
        try (PreparedStatement query = database.prepareStatement("""
            SELECT e.id,a.id,a.location,a.type,e.type,e.occurred_at,a.close_reason
            FROM alert_events e JOIN alerts a ON a.id=e.alert_id WHERE e.id>? ORDER BY e.id
            """)) {
            query.setLong(1, cursor);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(new Transition(rows.getLong(1), rows.getLong(2), rows.getString(3),
                    rows.getString(4), rows.getString(5), Instant.ofEpochMilli(rows.getLong(6)),
                    "CLOSED".equals(rows.getString(5)) ? rows.getString(7) : null));
            }
        }
        return result;
    }

    private record RecordedSample(long time, Observation temperature, Observation humidity) {}
    record ZoneReading(String location, Observation temperature, Observation humidity) {}
    record ReplayAlert(long alertId, String location, String alertType, Instant openedAt, String evidence) {}
    record Transition(long id, long alertId, String location, String alertType, String event,
                      Instant occurredAt, String closeReason) {}
    record ReplayTick(String type, int tick, Instant measuredAt, int readingsThisTick, int processedReadings,
                      List<ZoneReading> zones, List<ReplayAlert> activeAlerts, List<Transition> transitions) {}
    record ReplayComplete(String type, int ticks, int processedReadings, Instant firstAt, Instant lastAt) {}
}
