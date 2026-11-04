package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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
