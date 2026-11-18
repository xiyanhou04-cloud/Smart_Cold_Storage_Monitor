package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Estimate;
import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Quality;
import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PredictionRepository {

    public record Checkpoint(Reading reading, String featureSnapshot) {}
    public record Latest(Reading reading, RiskLevel riskLevel, AlertSource source, String modelOrRuleVersion,
                         Instant predictedAt, Quality qualityFlag) {}

    private final JdbcClient jdbc;

    public PredictionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long readingId, Estimate estimate, Instant predictedAt, String snapshot) {
        jdbc.sql("""
                INSERT INTO predictions (reading_id, risk_level, source, model_or_rule_version,
                                         predicted_at, feature_snapshot, quality_flag)
                VALUES (:id, :level, :source, :version, :at, :snapshot, :quality)
                ON CONFLICT(reading_id) DO NOTHING
                """).param("id", readingId).param("level", estimate.level().name())
                .param("source", estimate.source().name()).param("version", estimate.version())
                .param("at", SqlTimestamps.format(predictedAt)).param("snapshot", snapshot)
                .param("quality", estimate.quality().name()).update();
    }

    public boolean exists(long readingId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM predictions WHERE reading_id = :id)")
                .param("id", readingId).query(Boolean.class).single();
    }

    public Optional<Checkpoint> precedingCheckpoint(Reading reading) {
        return jdbc.sql("""
                SELECT r.*, p.feature_snapshot FROM readings r JOIN predictions p ON p.reading_id = r.id
                WHERE r.session_id = :session AND r.measured_at < :until
                ORDER BY r.measured_at DESC LIMIT 1
                """).param("session", reading.sessionId()).param("until", SqlTimestamps.format(reading.measuredAt()))
                .query((rs, row) -> new Checkpoint(ReadingRepository.mapRow(rs, row), rs.getString("feature_snapshot")))
                .optional();
    }

    public List<Reading> historyThrough(Reading reading, Instant after) {
        return jdbc.sql("""
                SELECT * FROM readings WHERE session_id = :session AND measured_at > :after AND measured_at <= :until
                ORDER BY measured_at
                """).param("session", reading.sessionId()).param("after", after == null ? "" : SqlTimestamps.format(after))
                .param("until", SqlTimestamps.format(reading.measuredAt())).query(ReadingRepository::mapRow).list();
    }

    public List<Reading> pendingAfter(long session, Instant after, int limit) {
        return jdbc.sql("""
                SELECT r.* FROM readings r LEFT JOIN predictions p ON p.reading_id = r.id
                WHERE (r.session_id, r.measured_at) > (:session, :after) AND p.reading_id IS NULL
                ORDER BY r.session_id, r.measured_at LIMIT :limit
                """).param("session", session).param("after", after == null ? "" : SqlTimestamps.format(after))
                .param("limit", limit).query(ReadingRepository::mapRow).list();
    }

    public List<Latest> latest(String deviceId) {
        String deviceFilter = deviceId == null ? "" : " WHERE d.id = :device";
        var query = jdbc.sql("""
                SELECT r.*, p.risk_level, p.source, p.model_or_rule_version, p.predicted_at, p.quality_flag
                FROM devices d
                JOIN readings r ON r.id = (SELECT id FROM readings WHERE device_id = d.id ORDER BY measured_at DESC LIMIT 1)
                LEFT JOIN predictions p ON p.reading_id = r.id
                """ + deviceFilter + " ORDER BY d.id");
        if (deviceId != null) {
            query.param("device", deviceId);
        }
        return query.query((rs, row) -> {
            Reading reading = ReadingRepository.mapRow(rs, row);
            String risk = rs.getString("risk_level");
            return risk == null ? new Latest(reading, null, null, null, null, null)
                    : new Latest(reading, RiskLevel.valueOf(risk), AlertSource.valueOf(rs.getString("source")),
                            rs.getString("model_or_rule_version"), SqlTimestamps.parse(rs.getString("predicted_at")),
                            Quality.valueOf(rs.getString("quality_flag")));
        }).list();
    }
}
