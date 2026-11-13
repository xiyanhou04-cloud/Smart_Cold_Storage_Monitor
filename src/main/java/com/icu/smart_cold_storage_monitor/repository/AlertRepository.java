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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access for the {@code alerts} table. Alerts are never deleted. The acknowledging user's username is read with
 * a correlated subquery rather than a join, so the same column list also works in {@code RETURNING}.
 */
@Repository
public class AlertRepository {

    private static final String COLUMNS = """
            id, session_id, device_id, kind, severity, source, opened_at, last_seen_at, resolved_at, resolve_reason,
            metric, direction, opening_threshold_id, limit_value, opening_value, last_value, peak_value,
            model_or_rule_version, quality_flag, acknowledged_by, acknowledged_at, note,
            (SELECT u.username FROM users u WHERE u.id = acknowledged_by) AS acknowledged_by_username""";

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

    /**
     * Records the first acknowledgment of an alert. Returns empty when the alert does not exist or was already
     * acknowledged; the alert's resolution and evidence columns are left as they are.
     */
    public Optional<Alert> acknowledge(long id, long userId, Instant acknowledgedAt, String note) {
        return jdbc.sql("""
                        UPDATE alerts SET acknowledged_by = :userId, acknowledged_at = :acknowledgedAt, note = :note
                        WHERE id = :id AND acknowledged_at IS NULL
                        RETURNING\s""" + COLUMNS)
                .param("id", id)
                .param("userId", userId)
                .param("acknowledgedAt", SqlTimestamps.format(acknowledgedAt))
                .param("note", note)
                .query(AlertRepository::mapRow)
                .optional();
    }

    /** One page of matching alerts, most recently opened first. */
    public List<Alert> search(AlertFilter filter, long offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = whereClause(filter, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql("SELECT " + COLUMNS + " FROM alerts\n" + where
                        + "ORDER BY opened_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .query(AlertRepository::mapRow)
                .list();
    }

    public long count(AlertFilter filter) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT COUNT(*) FROM alerts\n" + whereClause(filter, params))
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * Builds the WHERE clause from fixed SQL fragments only; every filter value is bound as a named parameter.
     * Returns an empty string, or {@code "WHERE ...\n"}, and fills {@code params}.
     */
    private static String whereClause(AlertFilter filter, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        if (!filter.deviceIds().isEmpty()) {
            conditions.add("device_id IN (:deviceIds)");
            params.put("deviceIds", filter.deviceIds().stream().sorted().toList());
        }
        if (!filter.kinds().isEmpty()) {
            conditions.add("kind IN (:kinds)");
            params.put("kinds", filter.kinds().stream().map(Enum::name).sorted().toList());
        }
        if (filter.state() != null) {
            conditions.add(switch (filter.state()) {
                case ACTIVE -> "resolved_at IS NULL";
                case RESOLVED -> "resolved_at IS NOT NULL";
            });
        }
        if (filter.acknowledged() != null) {
            conditions.add(filter.acknowledged() ? "acknowledged_at IS NOT NULL" : "acknowledged_at IS NULL");
        }
        if (filter.from() != null) {
            conditions.add("opened_at >= :from");
            params.put("from", SqlTimestamps.format(filter.from()));
        }
        if (filter.to() != null) {
            conditions.add("opened_at < :to");
            params.put("to", SqlTimestamps.format(filter.to()));
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + "\n";
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
                nullableLong(rs, "acknowledged_by"), rs.getString("acknowledged_by_username"),
                instant(rs, "acknowledged_at"), rs.getString("note"));
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
