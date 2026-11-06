package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.AuditEntry;
import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access for the append-only {@code audit_events} table. There is intentionally no update or delete method.
 */
@Repository
public class AuditEventRepository {

    private final JdbcClient jdbc;

    public AuditEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the event and returns its generated id. */
    public long insert(AuditEvent event) {
        return jdbc.sql("""
                        INSERT INTO audit_events (actor_id, attempted_username, event_type, occurred_at, result,
                                                  target_type, target_id, details)
                        VALUES (:actorId, :attemptedUsername, :eventType, :occurredAt, :result,
                                :targetType, :targetId, :details)
                        RETURNING id
                        """)
                .param("actorId", event.actorId())
                .param("attemptedUsername", event.attemptedUsername())
                .param("eventType", event.eventType().name())
                .param("occurredAt", SqlTimestamps.format(event.occurredAt()))
                .param("result", event.result().name())
                .param("targetType", event.targetType())
                .param("targetId", event.targetId())
                .param("details", event.details())
                .query(Long.class)
                .single();
    }

    /** Most recent events first, bounded by {@code limit}. */
    public List<AuditEvent> findRecent(int limit) {
        return jdbc.sql("""
                        SELECT id, actor_id, attempted_username, event_type, occurred_at, result,
                               target_type, target_id, details
                        FROM audit_events
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query(AuditEventRepository::mapRow)
                .list();
    }

    /**
     * One page of events matching {@code filter}, most recent first, each with the actor's username.
     *
     * @param offset number of matching rows to skip
     * @param limit  maximum number of rows to return
     */
    public List<AuditEntry> search(AuditEventFilter filter, long offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = whereClause(filter, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql("""
                        SELECT e.id, e.actor_id, e.attempted_username, e.event_type, e.occurred_at, e.result,
                               e.target_type, e.target_id, e.details, u.username AS actor_username
                        FROM audit_events e
                        LEFT JOIN users u ON u.id = e.actor_id
                        """ + where + """
                        ORDER BY e.occurred_at DESC, e.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .params(params)
                .query((rs, rowNum) -> new AuditEntry(mapRow(rs, rowNum), rs.getString("actor_username")))
                .list();
    }

    /** Number of events matching {@code filter}. */
    public long count(AuditEventFilter filter) {
        Map<String, Object> params = new HashMap<>();
        String where = whereClause(filter, params);
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM audit_events e
                        LEFT JOIN users u ON u.id = e.actor_id
                        """ + where)
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * Builds the WHERE clause from fixed SQL fragments only; every filter value is bound as a named parameter.
     * Returns an empty string, or {@code "WHERE ...\n"}, and fills {@code params}.
     */
    private static String whereClause(AuditEventFilter filter, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        if (filter.from() != null) {
            conditions.add("e.occurred_at >= :from");
            params.put("from", SqlTimestamps.format(filter.from()));
        }
        if (filter.to() != null) {
            conditions.add("e.occurred_at < :to");
            params.put("to", SqlTimestamps.format(filter.to()));
        }
        if (filter.actor() != null) {
            conditions.add("(u.username = :actor OR e.attempted_username = :actor)");
            params.put("actor", filter.actor());
        }
        if (!filter.eventTypes().isEmpty()) {
            conditions.add("e.event_type IN (:eventTypes)");
            params.put("eventTypes", filter.eventTypes().stream().map(Enum::name).sorted().toList());
        }
        if (!filter.results().isEmpty()) {
            conditions.add("e.result IN (:results)");
            params.put("results", filter.results().stream().map(Enum::name).sorted().toList());
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + "\n";
    }

    private static AuditEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        long actorId = rs.getLong("actor_id");
        Long actor = rs.wasNull() ? null : actorId;
        return new AuditEvent(
                rs.getLong("id"),
                actor,
                rs.getString("attempted_username"),
                AuditEventType.valueOf(rs.getString("event_type")),
                SqlTimestamps.parse(rs.getString("occurred_at")),
                AuditResult.valueOf(rs.getString("result")),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("details"));
    }
}
