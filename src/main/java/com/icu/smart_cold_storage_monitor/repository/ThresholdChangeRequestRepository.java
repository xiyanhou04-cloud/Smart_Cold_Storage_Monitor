package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.ThresholdChangeRequest;
import com.icu.smart_cold_storage_monitor.model.ThresholdLimits;
import com.icu.smart_cold_storage_monitor.model.ThresholdRequestStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access for {@code threshold_change_requests}. Requests are never deleted; a pending request is concluded once
 * by {@link #conclude}.
 */
@Repository
public class ThresholdChangeRequestRepository {

    private static final String SELECT = """
            SELECT r.id, r.device_id, r.base_threshold_id, r.lower_c, r.upper_c, r.lower_humidity_pct,
                   r.upper_humidity_pct, r.status, r.requested_by, rq.username AS requested_by_username,
                   r.requested_at, r.reviewed_by, rv.username AS reviewed_by_username, r.reviewed_at,
                   r.review_note, r.applied_threshold_id
            FROM threshold_change_requests r
            JOIN users rq ON rq.id = r.requested_by
            LEFT JOIN users rv ON rv.id = r.reviewed_by
            """;

    private final JdbcClient jdbc;

    public ThresholdChangeRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a PENDING request and returns its id; a second pending request for the device raises
     * {@code DuplicateKeyException}.
     */
    public long insertPending(String deviceId, long baseThresholdId, ThresholdLimits limits, long requestedBy,
                              Instant requestedAt) {
        return jdbc.sql("""
                        INSERT INTO threshold_change_requests (device_id, base_threshold_id, lower_c, upper_c,
                                                               lower_humidity_pct, upper_humidity_pct, status,
                                                               requested_by, requested_at)
                        VALUES (:deviceId, :baseThresholdId, :lowerC, :upperC, :lowerHumidity, :upperHumidity,
                                'PENDING', :requestedBy, :requestedAt)
                        RETURNING id
                        """)
                .param("deviceId", deviceId)
                .param("baseThresholdId", baseThresholdId)
                .param("lowerC", limits.temperatureLowerC())
                .param("upperC", limits.temperatureUpperC())
                .param("lowerHumidity", limits.humidityLowerPct())
                .param("upperHumidity", limits.humidityUpperPct())
                .param("requestedBy", requestedBy)
                .param("requestedAt", SqlTimestamps.format(requestedAt))
                .query(Long.class)
                .single();
    }

    public Optional<ThresholdChangeRequest> findById(long id) {
        return jdbc.sql(SELECT + "WHERE r.id = :id")
                .param("id", id)
                .query(ThresholdChangeRequestRepository::mapRow)
                .optional();
    }

    public Optional<ThresholdChangeRequest> findPendingByDevice(String deviceId) {
        return jdbc.sql(SELECT + "WHERE r.device_id = :deviceId AND r.status = 'PENDING'")
                .param("deviceId", deviceId)
                .query(ThresholdChangeRequestRepository::mapRow)
                .optional();
    }

    /** Every pending request, at most one per device. */
    public List<ThresholdChangeRequest> findAllPending() {
        return jdbc.sql(SELECT + "WHERE r.status = 'PENDING' ORDER BY r.device_id")
                .query(ThresholdChangeRequestRepository::mapRow)
                .list();
    }

    /**
     * Moves a PENDING request to {@code status}. Returns false, changing nothing, when the request is no longer
     * pending.
     *
     * @param reviewedBy       the user concluding it, or {@code null} when the system does
     * @param appliedThreshold the version an approval created; {@code null} for any other status
     */
    public boolean conclude(long id, ThresholdRequestStatus status, Long reviewedBy, Instant reviewedAt,
                            String note, Long appliedThreshold) {
        return jdbc.sql("""
                        UPDATE threshold_change_requests
                        SET status = :status, reviewed_by = :reviewedBy, reviewed_at = :reviewedAt,
                            review_note = :note, applied_threshold_id = :applied
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("reviewedBy", reviewedBy)
                .param("reviewedAt", SqlTimestamps.format(reviewedAt))
                .param("note", note)
                .param("applied", appliedThreshold)
                .update() == 1;
    }

    /**
     * Criteria for {@link #search}; a {@code null} value or an empty collection means "any".
     *
     * @param requestedBy only requests made by this user
     */
    public record Filter(String deviceId, Collection<ThresholdRequestStatus> statuses, Long requestedBy) {
    }

    /** One page of matching requests, newest first. */
    public List<ThresholdChangeRequest> search(Filter filter, long offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = whereClause(filter, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(SELECT + where + " ORDER BY r.requested_at DESC, r.id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .query(ThresholdChangeRequestRepository::mapRow)
                .list();
    }

    public long count(Filter filter) {
        Map<String, Object> params = new HashMap<>();
        String where = whereClause(filter, params);
        return jdbc.sql("SELECT count(*) FROM threshold_change_requests r " + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    /** Fixed SQL fragments only; every value is a bound parameter. */
    private static String whereClause(Filter filter, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        if (filter.deviceId() != null) {
            conditions.add("r.device_id = :deviceId");
            params.put("deviceId", filter.deviceId());
        }
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            conditions.add("r.status IN (:statuses)");
            params.put("statuses", filter.statuses().stream().map(Enum::name).distinct().toList());
        }
        if (filter.requestedBy() != null) {
            conditions.add("r.requested_by = :requestedBy");
            params.put("requestedBy", filter.requestedBy());
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }

    private static ThresholdChangeRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
        ThresholdLimits limits = new ThresholdLimits(rs.getDouble("lower_c"), rs.getDouble("upper_c"),
                rs.getDouble("lower_humidity_pct"), rs.getDouble("upper_humidity_pct"));
        long reviewedBy = rs.getLong("reviewed_by");
        Long reviewer = rs.wasNull() ? null : reviewedBy;
        String reviewedAt = rs.getString("reviewed_at");
        long applied = rs.getLong("applied_threshold_id");
        Long appliedThreshold = rs.wasNull() ? null : applied;
        return new ThresholdChangeRequest(rs.getLong("id"), rs.getString("device_id"), rs.getLong("base_threshold_id"),
                limits, ThresholdRequestStatus.valueOf(rs.getString("status")), rs.getLong("requested_by"),
                rs.getString("requested_by_username"), SqlTimestamps.parse(rs.getString("requested_at")), reviewer,
                rs.getString("reviewed_by_username"), reviewedAt == null ? null : SqlTimestamps.parse(reviewedAt),
                rs.getString("review_note"), appliedThreshold);
    }
}
