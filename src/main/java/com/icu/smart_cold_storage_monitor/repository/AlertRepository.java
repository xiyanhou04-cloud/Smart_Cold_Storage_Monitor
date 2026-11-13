package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertDirection;
import com.icu.smart_cold_storage_monitor.model.AlertKind;
import com.icu.smart_cold_storage_monitor.model.AlertResolveReason;
import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.ReadingMetric;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for the {@code alerts} table. Alerts are never deleted. */
@Repository
public class AlertRepository {

    private static final String COLUMNS = """
            id, session_id, device_id, kind, severity, source, opened_at, last_seen_at, resolved_at, resolve_reason,
            metric, direction, opening_threshold_id, limit_value, opening_value, last_value, peak_value,
            model_or_rule_version, quality_flag, acknowledged_by, acknowledged_at, note""";

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Alert> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alerts WHERE id = :id")
                .param("id", id)
                .query(AlertRepository::mapRow)
                .optional();
    }

    /** Every alert of the session, oldest first. */
    public List<Alert> findBySession(long sessionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM alerts WHERE session_id = :sessionId ORDER BY id")
                .param("sessionId", sessionId)
                .query(AlertRepository::mapRow)
                .list();
    }

    /** The session's active threshold alerts: at most one per metric. */
    public List<Alert> findActiveThresholdAlerts(long sessionId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM alerts
                        WHERE session_id = :sessionId AND kind = 'THRESHOLD' AND resolved_at IS NULL
                        ORDER BY id
                        """)
                .param("sessionId", sessionId)
                .query(AlertRepository::mapRow)
                .list();
    }

    /** Values of a new threshold alert; its opening value is also its last and peak value. */
    public record NewThresholdAlert(long sessionId, String deviceId, RiskLevel severity, ReadingMetric metric,
                                    AlertDirection direction, long thresholdId, double limitValue, double value,
                                    Instant measuredAt) {
    }

    public Alert insertThresholdAlert(NewThresholdAlert alert) {
        return jdbc.sql("""
                        INSERT INTO alerts (session_id, device_id, kind, severity, source, opened_at, last_seen_at,
                                            metric, direction, opening_threshold_id, limit_value, opening_value,
                                            last_value, peak_value)
                        VALUES (:sessionId, :deviceId, 'THRESHOLD', :severity, 'THRESHOLD', :measuredAt, :measuredAt,
                                :metric, :direction, :thresholdId, :limitValue, :value, :value, :value)
                        RETURNING\s""" + COLUMNS)
                .param("sessionId", alert.sessionId())
                .param("deviceId", alert.deviceId())
                .param("severity", alert.severity().name())
                .param("measuredAt", SqlTimestamps.format(alert.measuredAt()))
                .param("metric", alert.metric().name())
                .param("direction", alert.direction().name())
                .param("thresholdId", alert.thresholdId())
                .param("limitValue", alert.limitValue())
                .param("value", alert.value())
                .query(AlertRepository::mapRow)
                .single();
    }

    /** Records a further out-of-range reading on an active threshold alert. */
    public Alert updateThresholdEvidence(long id, Instant measuredAt, double lastValue, double peakValue) {
        return jdbc.sql("""
                        UPDATE alerts SET last_seen_at = :measuredAt, last_value = :lastValue, peak_value = :peakValue
                        WHERE id = :id AND resolved_at IS NULL
                        RETURNING\s""" + COLUMNS)
                .param("id", id)
                .param("measuredAt", SqlTimestamps.format(measuredAt))
                .param("lastValue", lastValue)
                .param("peakValue", peakValue)
                .query(AlertRepository::mapRow)
                .single();
    }

    /** Ends an active alert; {@code resolvedAt} is the measured time of the reading that ended it. */
    public Alert resolve(long id, Instant resolvedAt, AlertResolveReason reason) {
        return jdbc.sql("""
                        UPDATE alerts SET resolved_at = :resolvedAt, resolve_reason = :reason
                        WHERE id = :id AND resolved_at IS NULL
                        RETURNING\s""" + COLUMNS)
                .param("id", id)
                .param("resolvedAt", SqlTimestamps.format(resolvedAt))
                .param("reason", reason.name())
                .query(AlertRepository::mapRow)
                .single();
    }

    private static Alert mapRow(ResultSet rs, int rowNum) throws SQLException {
        String metric = rs.getString("metric");
        String direction = rs.getString("direction");
        String resolveReason = rs.getString("resolve_reason");
        return new Alert(rs.getLong("id"), rs.getLong("session_id"), rs.getString("device_id"),
                AlertKind.valueOf(rs.getString("kind")), RiskLevel.valueOf(rs.getString("severity")),
                AlertSource.valueOf(rs.getString("source")),
                instant(rs, "opened_at"), instant(rs, "last_seen_at"), instant(rs, "resolved_at"),
                resolveReason == null ? null : AlertResolveReason.valueOf(resolveReason),
                metric == null ? null : ReadingMetric.valueOf(metric),
                direction == null ? null : AlertDirection.valueOf(direction),
                nullableLong(rs, "opening_threshold_id"), nullableDouble(rs, "limit_value"),
                nullableDouble(rs, "opening_value"), nullableDouble(rs, "last_value"),
                nullableDouble(rs, "peak_value"),
                rs.getString("model_or_rule_version"), rs.getString("quality_flag"),
                nullableLong(rs, "acknowledged_by"), instant(rs, "acknowledged_at"), rs.getString("note"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        return text == null ? null : SqlTimestamps.parse(text);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
