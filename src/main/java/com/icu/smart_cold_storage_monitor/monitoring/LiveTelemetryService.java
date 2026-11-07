package com.icu.smart_cold_storage_monitor.monitoring;

import com.icu.smart_cold_storage_monitor.monitoring.LiveTelemetryController.Channel;
import com.icu.smart_cold_storage_monitor.monitoring.LiveTelemetryController.Submission;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("monitoring-live")
class LiveTelemetryService {
    static final long FRESHNESS_MS = 15_000;
    private static final String SAMPLE_COLUMNS = """
        id,device_id,location,boot_id,sequence,measured_at,received_at,
        temperature,temperature_state,humidity,humidity_state
        """;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final long startedAt;
    private final String jdbcUrl;
    private final List<Zone> zones;
    private final Map<String, Zone> devices;

    LiveTelemetryService(MonitoringProperties properties, Clock clock, ObjectMapper mapper,
                         @Value("${app.telemetry.database-path}") Path path) throws IOException, SQLException {
        this.clock = clock;
        this.mapper = mapper;
        startedAt = clock.millis();
        path = path.toAbsolutePath().normalize();
        if (path.equals(properties.getInventoryPath().toAbsolutePath().normalize())
                || Files.exists(path) && Files.isSameFile(path, properties.getInventoryPath())) {
            throw new IllegalArgumentException("Telemetry and inventory require separate database files.");
        }
        Files.createDirectories(path.getParent());
        jdbcUrl = "jdbc:sqlite:" + path.toUri();
        zones = loadZones(properties);
        Map<String, Zone> byDevice = new HashMap<>();
        for (Zone zone : zones) {
            if (byDevice.put(zone.device(), zone) != null) {
                throw new IllegalArgumentException("Assign each live device to exactly one zone.");
            }
        }
        devices = Map.copyOf(byDevice);
        initialize();
    }

    static List<Zone> loadZones(MonitoringProperties properties) throws SQLException {
        var mapping = properties.getLocationDevices();
        String uri = properties.getInventoryPath().toAbsolutePath().normalize().toUri().toString();
        List<Zone> result = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + uri + "?mode=ro");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT code,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct FROM locations")) {
            while (rs.next()) {
                String code = rs.getString(1);
                if (mapping.containsKey(code)) {
                    result.add(new Zone(code, mapping.get(code),
                        new Bounds(number(rs,2), number(rs,3)), new Bounds(number(rs,4), number(rs,5))));
                }
            }
        }
        if (result.isEmpty() || result.size() != mapping.size()) {
            throw new IllegalArgumentException("Live monitoring requires a valid inventory location for each device.");
        }
        return List.copyOf(result);
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException ex) {
            connection.close();
            throw ex;
        }
        return connection;
    }

    private void initialize() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            createSchema(s);
        }
    }

    static void createSchema(Statement s) throws SQLException {
        s.execute("""
            CREATE TABLE IF NOT EXISTS sensor_samples (
              id INTEGER PRIMARY KEY, device_id TEXT NOT NULL, location TEXT NOT NULL,
              boot_id TEXT NOT NULL, sequence INTEGER NOT NULL, measured_at INTEGER NOT NULL,
              received_at INTEGER NOT NULL, temperature REAL, temperature_state TEXT NOT NULL,
              humidity REAL, humidity_state TEXT NOT NULL, payload TEXT NOT NULL,
              UNIQUE(device_id,boot_id,sequence)
            )
            """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_sensor_latest ON sensor_samples(device_id,measured_at DESC,id DESC)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_sensor_boot ON sensor_samples(boot_id,id DESC)");
        s.execute("""
            CREATE TABLE IF NOT EXISTS alerts (
              id INTEGER PRIMARY KEY, location TEXT NOT NULL, device_id TEXT NOT NULL, type TEXT NOT NULL,
              opened_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, closed_at INTEGER,
              close_reason TEXT, observed_value REAL, threshold REAL, evidence TEXT NOT NULL,
              sample_id INTEGER REFERENCES sensor_samples(id)
            )
            """);
        s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_open_alert ON alerts(location,device_id,type) WHERE closed_at IS NULL");
        s.execute("""
            CREATE TABLE IF NOT EXISTS alert_events (
              id INTEGER PRIMARY KEY, alert_id INTEGER NOT NULL REFERENCES alerts(id),
              type TEXT NOT NULL, occurred_at INTEGER NOT NULL, sample_id INTEGER REFERENCES sensor_samples(id)
            )
            """);
    }

    boolean knowsDevice(String device) { return devices.containsKey(device); }

    synchronized WriteResult ingest(String device, Submission input) {
        Instant received = clock.instant();
        if (input.bootId() == null || !input.bootId().matches("[A-Za-z0-9_-]{1,80}")
                || input.sequence() == null || input.sequence() < 0) {
            throw new InvalidInputException("bootId/sequence", "Expected a boot identifier and a nonnegative sequence.");
        }
        if (input.measuredAt() == null || input.measuredAt().isAfter(received.plusSeconds(2))
                || input.measuredAt().isBefore(Instant.EPOCH)) {
            throw new InvalidInputException("measuredAt", "Expected a UTC sample time with at most two seconds of future skew.");
        }
        if (!Boolean.TRUE.equals(input.synthetic())) {
            throw new InvalidInputException("synthetic", "This live demo accepts explicitly synthetic samples.");
        }
        String raw = input.rawFrameHex();
        if (raw != null && raw.length() > 64) throw new InvalidInputException("rawFrameHex", "Frame is too long.");
        byte[] frame = parseFrame(raw);
        Observation temperature = observation(input.temperature(), frame, true);
        Observation humidity = observation(input.humidity(), frame, false);
        String payload = mapper.writeValueAsString(input);
        try (Connection c = connect()) {
            try (PreparedStatement insert = c.prepareStatement("""
                INSERT INTO sensor_samples(device_id,location,boot_id,sequence,measured_at,received_at,
                  temperature,temperature_state,humidity,humidity_state,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(device_id,boot_id,sequence) DO NOTHING
                """)) {
                bind(insert, device, devices.get(device).code(), input.bootId(), input.sequence(),
                    input.measuredAt().toEpochMilli(), received.toEpochMilli(), temperature.value(),
                    temperature.state().name(), humidity.value(), humidity.state().name(), payload);
                if (insert.executeUpdate() == 1) return WriteResult.CREATED;
            }
            try (PreparedStatement select = c.prepareStatement(
                    "SELECT payload FROM sensor_samples WHERE device_id=? AND boot_id=? AND sequence=?")) {
                bind(select, device, input.bootId(), input.sequence());
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() && payload.equals(rs.getString(1)) ? WriteResult.DUPLICATE : WriteResult.CONFLICT;
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Telemetry sample could not be saved.", ex);
        }
    }

    static byte[] parseFrame(String raw) {
        if (raw == null) return null;
        try { return HexFormat.of().parseHex(raw.replace(" ", "")); }
        catch (IllegalArgumentException ex) { return new byte[0]; }
    }

    static Observation observation(Channel channel, byte[] frame, boolean temperature) {
        if (channel == null) return new Observation(Quality.MISSING, null);
        String state = channel.state();
        if ("MISSING".equals(state) && channel.value() == null) return new Observation(Quality.MISSING, null);
        if (state != null && !"PRESENT".equals(state)) return new Observation(Quality.INVALID, null);
        Double value = channel.value();
        if (value == null && frame == null) return new Observation(Quality.MISSING, null);
        if (value != null && (!Double.isFinite(value) || value < (temperature ? -40 : 0)
                || value > (temperature ? 125 : 100))) {
            return new Observation(Quality.INVALID, null);
        }
        if (frame != null) {
            try {
                double decoded = Sht45Codec.decodeChannel(frame, temperature);
                if (value != null && Math.abs(value - decoded) > 0.01) {
                    return new Observation(Quality.INVALID, null);
                }
                value = decoded;
            } catch (IllegalArgumentException ex) {
                return new Observation(Quality.INVALID, null);
            }
        }
        return new Observation(Quality.VALID, value);
    }

    @Scheduled(fixedRateString = "${app.telemetry.tick-ms:5000}")
    synchronized void evaluate() {
        long now = clock.millis();
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                for (Zone zone : zones) {
                    Sample sample = latest(c, zone, now);
                    if (sample == null && now - startedAt < FRESHNESS_MS) continue;
                    boolean fresh = sample != null && now - sample.measuredAt().toEpochMilli() <= FRESHNESS_MS;
                    Long sampleId = fresh ? sample.id() : null;
                    check(c, zone, "TEMPERATURE", zone.temperature(),
                        fresh ? new Observation(sample.temperatureState(), sample.temperatureC()) : null, sampleId, now);
                    check(c, zone, "HUMIDITY", zone.humidity(),
                        fresh ? new Observation(sample.humidityState(), sample.humidityPct()) : null, sampleId, now);
                }
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Telemetry evaluation failed.", ex);
        }
    }

    private static Sample latest(Connection c, Zone zone, long now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + SAMPLE_COLUMNS + """
             FROM sensor_samples WHERE device_id=? AND location=? AND measured_at<=?
             ORDER BY measured_at DESC,id DESC LIMIT 1
            """)) {
            bind(s, zone.device(), zone.code(), now);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? sample(rs) : null; }
        }
    }

    static void check(Connection c, Zone zone, String channel, Bounds bounds,
                              Observation observation, Long sampleId, long now) throws SQLException {
        String sensor = "SENSOR_" + channel + "_";
        String environment = "ZONE_" + channel + "_";
        Quality quality = observation == null ? Quality.MISSING : observation.state();
        Double value = observation == null ? null : observation.value();
        String fault = null;
        Double threshold = null;
        if (quality != Quality.VALID) {
            fault = sensor + quality.name();
        } else if (bounds.min() != null && value < bounds.min()) {
            fault = environment + "LOW";
            threshold = bounds.min();
        } else if (bounds.max() != null && value > bounds.max()) {
            fault = environment + "HIGH";
            threshold = bounds.max();
        }
        for (String type : List.of(sensor+"MISSING", sensor+"INVALID", environment+"LOW", environment+"HIGH")) {
            Long id = openId(c, zone, type);
            if (type.equals(fault)) {
                String evidence = observation == null ? "STALE_OR_SILENT" : quality.name();
                if (id == null) open(c, zone, type, now, sampleId, value, threshold, evidence);
                else update(c, id, now, sampleId, value, threshold, evidence);
            } else if (id != null) {
                if (type.startsWith(environment) && quality != Quality.VALID) {
                    try (PreparedStatement s = c.prepareStatement("UPDATE alerts SET evidence='UNKNOWN' WHERE id=?")) {
                        bind(s, id); s.executeUpdate();
                    }
                } else {
                    close(c, id, now, sampleId, quality == Quality.VALID ? "RECOVERED" : "SUPERSEDED");
                }
            }
        }
    }

    private static Long openId(Connection c, Zone zone, String type) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id FROM alerts WHERE location=? AND device_id=? AND type=? AND closed_at IS NULL")) {
            bind(s, zone.code(), zone.device(), type);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        }
    }

    private static void open(Connection c, Zone zone, String type, long now, Long sample,
                             Double value, Double threshold, String evidence) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
            INSERT INTO alerts(location,device_id,type,opened_at,last_seen_at,sample_id,observed_value,threshold,evidence)
            VALUES(?,?,?,?,?,?,?,?,?) RETURNING id
            """)) {
            bind(s, zone.code(), zone.device(), type, now, now, sample, value, threshold, evidence);
            long id;
            try (ResultSet rs = s.executeQuery()) { rs.next(); id = rs.getLong(1); }
            event(c, id, "OPENED", now, sample);
        }
    }

    private static void update(Connection c, long id, long now, Long sample,
                               Double value, Double threshold, String evidence) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
            UPDATE alerts SET last_seen_at=?,sample_id=?,observed_value=?,threshold=?,evidence=?
            WHERE id=? AND (sample_id IS NOT ? OR evidence<>?)
            """)) {
            bind(s, now, sample, value, threshold, evidence, id, sample, evidence);
            s.executeUpdate();
        }
    }

    private static void close(Connection c, long id, long now, Long sample, String reason) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE alerts SET closed_at=?,close_reason=? WHERE id=?")) {
            bind(s, now, reason, id); s.executeUpdate();
        }
        event(c, id, "CLOSED", now, sample);
    }

    private static void event(Connection c, long id, String type, long now, Long sample) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO alert_events(alert_id,type,occurred_at,sample_id) VALUES(?,?,?,?)")) {
            bind(s, id, type, now, sample); s.executeUpdate();
        }
    }

    List<Alert> alerts(String state, int limit) {
        String predicate = switch (state) {
            case "OPEN" -> " WHERE closed_at IS NULL";
            case "CLOSED" -> " WHERE closed_at IS NOT NULL";
            default -> "";
        };
        return query("SELECT * FROM alerts" + predicate + " ORDER BY id DESC LIMIT ?", rs -> {
            long closedValue = rs.getLong("closed_at");
            Long closed = rs.wasNull() ? null : closedValue;
            return new Alert(rs.getLong("id"), rs.getString("location"), rs.getString("device_id"),
                rs.getString("type"), closed == null ? "OPEN" : "CLOSED", instant(rs,"opened_at"),
                instant(rs,"last_seen_at"), closed == null ? null : Instant.ofEpochMilli(closed),
                rs.getString("close_reason"), number(rs,"observed_value"), number(rs,"threshold"), rs.getString("evidence"));
        }, limit);
    }

    List<AlertEvent> events(long afterId, int limit) {
        return query("""
            SELECT e.*,a.location,a.device_id,a.type AS alert_type FROM alert_events e
            JOIN alerts a ON a.id=e.alert_id WHERE e.id>? ORDER BY e.id LIMIT ?
            """, rs -> new AlertEvent(rs.getLong("id"),rs.getLong("alert_id"),rs.getString("type"),
                instant(rs,"occurred_at"),rs.getString("location"),rs.getString("device_id"),rs.getString("alert_type")),
            afterId, limit);
    }

    List<Sample> samples(String bootId, int limit) {
        return query("SELECT " + SAMPLE_COLUMNS + " FROM sensor_samples WHERE boot_id=? ORDER BY id DESC LIMIT ?",
            LiveTelemetryService::sample, bootId, limit);
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        List<T> result = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) result.add(mapper.map(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Telemetry query failed.", ex);
        }
        return result;
    }

    private static Sample sample(ResultSet rs) throws SQLException {
        return new Sample(rs.getLong("id"),rs.getString("device_id"),rs.getString("location"),rs.getString("boot_id"),
            rs.getLong("sequence"),instant(rs,"measured_at"),instant(rs,"received_at"),
            number(rs,"temperature"),Quality.valueOf(rs.getString("temperature_state")),
            number(rs,"humidity"),Quality.valueOf(rs.getString("humidity_state")));
    }

    private static Instant instant(ResultSet rs,String column) throws SQLException {
        return Instant.ofEpochMilli(rs.getLong(column));
    }
    private static Double number(ResultSet rs,String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
    private static Double number(ResultSet rs,int column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    @FunctionalInterface private interface RowMapper<T> { T map(ResultSet rs) throws SQLException; }
    record Zone(String code, String device, Bounds temperature, Bounds humidity) {}
    record Bounds(Double min, Double max) {}
    record Observation(Quality state, Double value) {}
    enum Quality { VALID, MISSING, INVALID }
    enum WriteResult { CREATED, DUPLICATE, CONFLICT }
    record Sample(long id, String deviceId, String location, String bootId, long sequence, Instant measuredAt,
                  Instant receivedAt, Double temperatureC, Quality temperatureState,
                  Double humidityPct, Quality humidityState) {}
    record Alert(long id, String location, String deviceId, String type, String state, Instant openedAt,
                 Instant lastSeenAt, Instant closedAt, String closeReason, Double observedValue,
                 Double threshold, String evidence) {}
    record AlertEvent(long id, long alertId, String type, Instant occurredAt, String location,
                      String deviceId, String alertType) {}
}
