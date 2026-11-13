package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.model.StorageSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for the {@code storage_sessions} table. */
@Repository
public class StorageSessionRepository {

    private static final String COLUMNS = "id, device_id, product_type, started_at, ended_at";

    private final JdbcClient jdbc;

    public StorageSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StorageSession> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM storage_sessions WHERE id = :id")
                .param("id", id)
                .query(StorageSessionRepository::mapRow)
                .optional();
    }

    public Optional<StorageSession> findActiveByDevice(String deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM storage_sessions WHERE device_id = :deviceId AND ended_at IS NULL")
                .param("deviceId", deviceId)
                .query(StorageSessionRepository::mapRow)
                .optional();
    }

    /** Inserts an active session; a second active session for the device raises {@code DuplicateKeyException}. */
    public long insert(String deviceId, String productType, Instant startedAt) {
        return jdbc.sql("""
                        INSERT INTO storage_sessions (device_id, product_type, started_at)
                        VALUES (:deviceId, :productType, :startedAt)
                        RETURNING id
                        """)
                .param("deviceId", deviceId)
                .param("productType", productType)
                .param("startedAt", SqlTimestamps.format(startedAt))
                .query(Long.class)
                .single();
    }

    private static StorageSession mapRow(ResultSet rs, int rowNum) throws SQLException {
        String endedAt = rs.getString("ended_at");
        return new StorageSession(rs.getLong("id"), rs.getString("device_id"), rs.getString("product_type"),
                SqlTimestamps.parse(rs.getString("started_at")), endedAt == null ? null : SqlTimestamps.parse(endedAt));
    }
}
