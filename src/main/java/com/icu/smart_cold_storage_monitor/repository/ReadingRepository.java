package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.Reading;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for the {@code readings} table. */
@Repository
public class ReadingRepository {

    private static final String COLUMNS =
            "id, device_id, session_id, measured_at, received_at, temperature_c, humidity_pct, threshold_id";

    private final JdbcClient jdbc;

    public ReadingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Reading> findByDeviceAndMeasuredAt(String deviceId, Instant measuredAt) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM readings WHERE device_id = :deviceId AND measured_at = :measuredAt")
                .param("deviceId", deviceId)
                .param("measuredAt", SqlTimestamps.format(measuredAt))
                .query(ReadingRepository::mapRow)
                .optional();
    }

    /** Latest measured time stored for the device, served by the UNIQUE (device_id, measured_at) index. */
    public Optional<Instant> findLatestMeasuredAt(String deviceId) {
        return jdbc.sql("""
                        SELECT measured_at FROM readings WHERE device_id = :deviceId
                        ORDER BY measured_at DESC LIMIT 1
                        """)
                .param("deviceId", deviceId)
                .query(String.class)
                .optional()
                .map(SqlTimestamps::parse);
    }

    public Reading insert(String deviceId, long sessionId, Instant measuredAt, Instant receivedAt,
                          double temperatureC, double humidityPct, Long thresholdId) {
        return jdbc.sql("""
                        INSERT INTO readings (device_id, session_id, measured_at, received_at, temperature_c,
                                              humidity_pct, threshold_id)
                        VALUES (:deviceId, :sessionId, :measuredAt, :receivedAt, :temperatureC, :humidityPct,
                                :thresholdId)
                        RETURNING\s""" + COLUMNS)
                .param("deviceId", deviceId)
                .param("sessionId", sessionId)
                .param("measuredAt", SqlTimestamps.format(measuredAt))
                .param("receivedAt", SqlTimestamps.format(receivedAt))
                .param("temperatureC", temperatureC)
                .param("humidityPct", humidityPct)
                .param("thresholdId", thresholdId)
                .query(ReadingRepository::mapRow)
                .single();
    }

    /** Deletes every reading and returns how many were removed. */
    public int deleteAll() {
        return jdbc.sql("DELETE FROM readings").update();
    }

    private static Reading mapRow(ResultSet rs, int rowNum) throws SQLException {
        long thresholdId = rs.getLong("threshold_id");
        Long threshold = rs.wasNull() ? null : thresholdId;
        return new Reading(rs.getLong("id"), rs.getString("device_id"), rs.getLong("session_id"),
                SqlTimestamps.parse(rs.getString("measured_at")), SqlTimestamps.parse(rs.getString("received_at")),
                rs.getDouble("temperature_c"), rs.getDouble("humidity_pct"), threshold);
    }
}
