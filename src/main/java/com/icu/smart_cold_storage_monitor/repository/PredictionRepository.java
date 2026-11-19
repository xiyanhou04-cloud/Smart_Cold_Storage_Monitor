package com.icu.smart_cold_storage_monitor.repository;

import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Estimate;
import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Quality;
import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.Prediction;
import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@DependsOnDatabaseInitialization
public class PredictionRepository {

    public record Checkpoint(Reading reading, String featureSnapshot) {}
    public record Pending(Reading reading, int failedAttempts) {}
    public record Latest(Reading reading, RiskLevel riskLevel, AlertSource source, String modelOrRuleVersion,
                         Instant predictedAt, Quality qualityFlag, Boolean historicalExposureIncomplete,
                         String currentGapReason, String lastGapReason) {}

    /** Columns of a prediction with its reading; feature and gap fields are read from the stored snapshot. */
    private static final String HISTORY_COLUMNS = """
            p.id, p.reading_id, r.device_id, r.session_id, r.measured_at, r.temperature_c, r.humidity_pct,
            r.threshold_id, p.risk_level, p.source, p.model_or_rule_version, p.predicted_at, p.quality_flag,
            CASE WHEN json_valid(p.feature_snapshot) THEN
              json_extract(p.feature_snapshot, '$.features.observedAboveUpperSeconds') END AS observed_above_upper,
            CASE WHEN json_valid(p.feature_snapshot) THEN
              json_extract(p.feature_snapshot, '$.features.elapsedStorageSeconds') END AS elapsed_storage,
            CASE WHEN json_valid(p.feature_snapshot) THEN
              json_extract(p.feature_snapshot, '$.features.productType') END AS product_type,
            CASE WHEN json_valid(p.feature_snapshot) THEN
              json_extract(p.feature_snapshot, '$.features.incompleteHistory') END AS incomplete_history,
            CASE WHEN json_valid(p.feature_snapshot) THEN
              json_extract(p.feature_snapshot, '$.features.currentIssue') END AS current_gap_reason,
            CASE WHEN json_valid(p.feature_snapshot) THEN
              json_extract(p.feature_snapshot, '$.features.lastGapReason') END AS last_gap_reason""";

    private final JdbcClient jdbc;

    public PredictionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of matching predictions, most recently measured reading first. */
    public List<Prediction> search(PredictionFilter filter, long offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = historyWhere(filter, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql("SELECT " + HISTORY_COLUMNS + "\nFROM predictions p JOIN readings r ON r.id = p.reading_id\n"
                        + where + "ORDER BY r.measured_at DESC, p.id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .query(PredictionRepository::mapPrediction)
                .list();
    }

    public long count(PredictionFilter filter) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT COUNT(*) FROM predictions p JOIN readings r ON r.id = p.reading_id\n"
                        + historyWhere(filter, params))
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * Builds the WHERE clause from fixed SQL fragments only; every filter value is bound as a named parameter.
     * Returns an empty string, or {@code "WHERE ...\n"}, and fills {@code params}.
     */
    private static String historyWhere(PredictionFilter filter, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        if (!filter.deviceIds().isEmpty()) {
            conditions.add("r.device_id IN (:deviceIds)");
            params.put("deviceIds", filter.deviceIds().stream().sorted().toList());
        }
        if (!filter.riskLevels().isEmpty()) {
            conditions.add("p.risk_level IN (:riskLevels)");
            params.put("riskLevels", filter.riskLevels().stream().map(Enum::name).sorted().toList());
        }
        if (filter.from() != null) {
            conditions.add("r.measured_at >= :from");
            params.put("from", SqlTimestamps.formatRangeBound(filter.from()));
        }
        if (filter.to() != null) {
            conditions.add("r.measured_at < :to");
            params.put("to", SqlTimestamps.formatRangeBound(filter.to()));
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + "\n";
    }

    private static Prediction mapPrediction(ResultSet rs, int rowNum) throws SQLException {
        long thresholdId = rs.getLong("threshold_id");
        Long threshold = rs.wasNull() ? null : thresholdId;
        double observed = rs.getDouble("observed_above_upper");
        Double observedAboveUpper = rs.wasNull() ? null : observed;
        double elapsed = rs.getDouble("elapsed_storage");
        Double elapsedStorage = rs.wasNull() ? null : elapsed;
        boolean incomplete = rs.getBoolean("incomplete_history");
        Boolean historical = rs.wasNull() ? null : incomplete;
        String lastGap = rs.getString("last_gap_reason");
        return new Prediction(rs.getLong("id"), rs.getLong("reading_id"), rs.getString("device_id"),
                rs.getLong("session_id"), SqlTimestamps.parse(rs.getString("measured_at")),
                rs.getDouble("temperature_c"), rs.getDouble("humidity_pct"), threshold,
                RiskLevel.valueOf(rs.getString("risk_level")), AlertSource.valueOf(rs.getString("source")),
                rs.getString("model_or_rule_version"), SqlTimestamps.parse(rs.getString("predicted_at")),
                rs.getString("quality_flag"), observedAboveUpper, elapsedStorage, rs.getString("product_type"),
                historical, rs.getString("current_gap_reason"),
                // Snapshots written before gap reasons existed only carry the history flag.
                lastGap == null && Boolean.TRUE.equals(historical) ? "LEGACY_UNATTRIBUTED" : lastGap);
    }

    @PostConstruct
    void installTriggers() {
        // Submit each SQLite trigger as one statement so the schema script splitter never splits its body.
        jdbc.sql("""
                CREATE TRIGGER IF NOT EXISTS enqueue_reading_prediction AFTER INSERT ON readings
                BEGIN INSERT INTO prediction_jobs (reading_id) VALUES (NEW.id); END
                """).update();
        jdbc.sql("""
                CREATE TRIGGER IF NOT EXISTS complete_reading_prediction AFTER INSERT ON predictions
                BEGIN DELETE FROM prediction_jobs WHERE reading_id = NEW.reading_id; END
                """).update();
    }

    /** Returns false when the reading already had a prediction, which is then kept as it is. */
    public boolean insert(long readingId, Estimate estimate, Instant predictedAt, String snapshot) {
        return jdbc.sql("""
                INSERT INTO predictions (reading_id, risk_level, source, model_or_rule_version,
                                         predicted_at, feature_snapshot, quality_flag)
                VALUES (:id, :level, :source, :version, :at, :snapshot, :quality)
                ON CONFLICT(reading_id) DO NOTHING
                """).param("id", readingId).param("level", estimate.level().name())
                .param("source", estimate.source().name()).param("version", estimate.version())
                .param("at", SqlTimestamps.format(predictedAt)).param("snapshot", snapshot)
                .param("quality", estimate.quality().name()).update() == 1;
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

    /** Backfills only missing work from databases that predate the enqueue trigger. */
    public void enqueueExisting() {
        jdbc.sql("""
                INSERT INTO prediction_jobs (reading_id)
                SELECT r.id FROM readings r
                WHERE NOT EXISTS (SELECT 1 FROM predictions p WHERE p.reading_id = r.id)
                  AND NOT EXISTS (SELECT 1 FROM prediction_jobs j WHERE j.reading_id = r.id)
                """).update();
    }

    public List<Pending> due(long nowMillis, int limit) {
        return jdbc.sql("""
                SELECT r.*, j.failed_attempts FROM prediction_jobs j JOIN readings r ON r.id = j.reading_id
                WHERE j.terminal = 0 AND j.next_attempt_ms <= :now
                ORDER BY r.session_id, r.measured_at LIMIT :limit
                """).param("now", nowMillis).param("limit", limit)
                .query((rs, row) -> new Pending(ReadingRepository.mapRow(rs, row), rs.getInt("failed_attempts")))
                .list();
    }

    public void recordFailure(long readingId, int attempts, long retryAt, boolean terminal, String code) {
        jdbc.sql("""
                UPDATE prediction_jobs SET failed_attempts=:attempts, next_attempt_ms=:retryAt,
                    terminal=:terminal, failure_code=:code WHERE reading_id=:id
                """).param("id", readingId).param("attempts", attempts).param("retryAt", retryAt)
                .param("terminal", terminal ? 1 : 0).param("code", code).update();
    }

    public List<Latest> latest(String deviceId) {
        String deviceFilter = deviceId == null ? "" : " WHERE d.id = :device";
        var query = jdbc.sql("""
                SELECT r.*, p.risk_level, p.source, p.model_or_rule_version, p.predicted_at, p.quality_flag,
                  CASE WHEN json_valid(p.feature_snapshot) THEN
                    json_extract(p.feature_snapshot, '$.features.incompleteHistory') END AS incomplete_history,
                  CASE WHEN json_valid(p.feature_snapshot) THEN
                    json_extract(p.feature_snapshot, '$.features.currentIssue') END AS current_gap_reason,
                  CASE WHEN json_valid(p.feature_snapshot) THEN
                    json_extract(p.feature_snapshot, '$.features.lastGapReason') END AS last_gap_reason
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
            boolean incomplete = rs.getBoolean("incomplete_history");
            Boolean historical = rs.wasNull() ? null : incomplete;
            String lastGap = rs.getString("last_gap_reason");
            return risk == null ? new Latest(reading, null, null, null, null, null, null, null, null)
                    : new Latest(reading, RiskLevel.valueOf(risk), AlertSource.valueOf(rs.getString("source")),
                            rs.getString("model_or_rule_version"), SqlTimestamps.parse(rs.getString("predicted_at")),
                            Quality.valueOf(rs.getString("quality_flag")), historical,
                            rs.getString("current_gap_reason"),
                            lastGap == null && Boolean.TRUE.equals(historical) ? "LEGACY_UNATTRIBUTED" : lastGap);
        }).list();
    }
}
