package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.model.ThresholdSetting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access for the append-only {@code threshold_settings} table: versions are inserted, never updated or deleted.
 * A session's current version is its row with the highest id, so ordering does not depend on clock values.
 */
@Repository
public class ThresholdSettingRepository {

    private static final String SELECT = """
            SELECT t.id, t.session_id, t.lower_c, t.upper_c, t.lower_humidity_pct, t.upper_humidity_pct,
                   t.effective_from, t.changed_by, u.username AS changed_by_username
            FROM threshold_settings t
            LEFT JOIN users u ON u.id = t.changed_by
            """;

    private final JdbcClient jdbc;

    public ThresholdSettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new version and returns its id.
     *
     * @param changedBy the user making the change, or {@code null} for a system default
     */
    public long insert(long sessionId, ThresholdLimits limits, Instant effectiveFrom, Long changedBy) {
        return jdbc.sql("""
                        INSERT INTO threshold_settings (session_id, lower_c, upper_c, lower_humidity_pct,
                                                        upper_humidity_pct, effective_from, changed_by)
                        VALUES (:sessionId, :lowerC, :upperC, :lowerHumidity, :upperHumidity, :effectiveFrom,
                                :changedBy)
                        RETURNING id
                        """)
                .param("sessionId", sessionId)
                .param("lowerC", limits.temperatureLowerC())
                .param("upperC", limits.temperatureUpperC())
                .param("lowerHumidity", limits.humidityLowerPct())
                .param("upperHumidity", limits.humidityUpperPct())
                .param("effectiveFrom", SqlTimestamps.format(effectiveFrom))
                .param("changedBy", changedBy)
                .query(Long.class)
                .single();
    }

    public Optional<ThresholdSetting> findById(long id) {
        return jdbc.sql(SELECT + "WHERE t.id = :id")
                .param("id", id)
                .query(ThresholdSettingRepository::mapRow)
                .optional();
    }

    /** The session's current version, or empty when it has none. */
    public Optional<ThresholdSetting> findCurrentBySession(long sessionId) {
        return jdbc.sql(SELECT + "WHERE t.session_id = :sessionId ORDER BY t.id DESC LIMIT 1")
                .param("sessionId", sessionId)
                .query(ThresholdSettingRepository::mapRow)
                .optional();
    }

    /** Id of the session's current version, or empty when it has none. */
    public Optional<Long> findCurrentIdBySession(long sessionId) {
        return jdbc.sql("SELECT id FROM threshold_settings WHERE session_id = :sessionId ORDER BY id DESC LIMIT 1")
                .param("sessionId", sessionId)
                .query(Long.class)
                .optional();
    }

    /** The current version of every active storage session that has one. */
    public List<ThresholdSetting> findCurrentForActiveSessions() {
        return jdbc.sql(SELECT + """
                        JOIN storage_sessions s ON s.id = t.session_id AND s.ended_at IS NULL
                        WHERE t.id = (SELECT max(id) FROM threshold_settings WHERE session_id = t.session_id)
                        ORDER BY t.session_id
                        """)
                .query(ThresholdSettingRepository::mapRow)
                .list();
    }

    /** An active storage session without any threshold version, with its device's location. */
    public record SessionWithoutThreshold(long sessionId, String deviceId, String locationCode) {
    }

    /** Active storage sessions that have no threshold version yet, e.g. sessions started before FR-04. */
    public List<SessionWithoutThreshold> findActiveSessionsWithoutThreshold() {
        return jdbc.sql("""
                        SELECT s.id, s.device_id, d.location_code
                        FROM storage_sessions s
                        JOIN devices d ON d.id = s.device_id
                        WHERE s.ended_at IS NULL
                          AND NOT EXISTS (SELECT 1 FROM threshold_settings t WHERE t.session_id = s.id)
                        ORDER BY s.id
                        """)
                .query((rs, rowNum) -> new SessionWithoutThreshold(rs.getLong("id"), rs.getString("device_id"),
                        rs.getString("location_code")))
                .list();
    }

    /**
     * Maps a row. A version stored without humidity limits (only possible for rows written outside the application
     * before the humidity columns existed) reads as the full 0-100 %RH range, i.e. no humidity limit.
     */
    private static ThresholdSetting mapRow(ResultSet rs, int rowNum) throws SQLException {
        double lowerHumidity = rs.getDouble("lower_humidity_pct");
        boolean noHumidity = rs.wasNull();
        double upperHumidity = rs.getDouble("upper_humidity_pct");
        noHumidity = noHumidity || rs.wasNull();
        ThresholdLimits limits = new ThresholdLimits(rs.getDouble("lower_c"), rs.getDouble("upper_c"),
                noHumidity ? ThresholdLimits.MIN_HUMIDITY_PCT : lowerHumidity,
                noHumidity ? ThresholdLimits.MAX_HUMIDITY_PCT : upperHumidity);
        long changedBy = rs.getLong("changed_by");
        Long changer = rs.wasNull() ? null : changedBy;
        return new ThresholdSetting(rs.getLong("id"), rs.getLong("session_id"), limits,
                SqlTimestamps.parse(rs.getString("effective_from")), changer, rs.getString("changed_by_username"));
    }
}
