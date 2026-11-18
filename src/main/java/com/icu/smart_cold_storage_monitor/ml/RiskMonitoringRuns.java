package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.ml.RiskFeatures.GapReason;
import com.icu.smart_cold_storage_monitor.repository.SqlTimestamps;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Durable monitoring boundaries. A process that cannot record its stop stays unconfirmed. */
@Component
@DependsOnDatabaseInitialization
class RiskMonitoringRuns implements SmartInitializingSingleton {

    private record Run(Instant started, Instant stopped) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private Long id;

    RiskMonitoringRuns(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        id = jdbc.sql("INSERT INTO risk_monitoring_runs (started_at) VALUES (?) RETURNING id")
                .param(SqlTimestamps.format(clock.instant())).query(Long.class).single();
    }

    @PreDestroy
    void recordOrderlyStop() {
        if (id != null) {
            jdbc.sql("UPDATE risk_monitoring_runs SET stopped_at = ? WHERE id = ?")
                    .param(SqlTimestamps.format(clock.instant())).param(id).update();
        }
    }

    GapReason interruptionBetween(Instant previous, Instant current, Instant previousReceipt, Instant currentReceipt) {
        if (currentReceipt.isBefore(previousReceipt)) {
            // We cannot assign a reliable run boundary when the server receipt clock moves backwards.
            return GapReason.UNCONFIRMED_MONITORING_INTERRUPTION;
        }
        List<Run> runs = jdbc.sql("""
                SELECT started_at, stopped_at FROM risk_monitoring_runs
                WHERE started_at <= :current AND
                  (started_at > :previous OR id = (
                    SELECT id FROM risk_monitoring_runs WHERE started_at <= :previous
                    ORDER BY started_at DESC, id DESC LIMIT 1))
                ORDER BY started_at, id
                """).param("previous", SqlTimestamps.format(previousReceipt))
                .param("current", SqlTimestamps.format(currentReceipt))
                .query((rs, n) -> new Run(SqlTimestamps.parse(rs.getString("started_at")),
                        rs.getString("stopped_at") == null ? null : SqlTimestamps.parse(rs.getString("stopped_at"))))
                .list();

        if (runs.isEmpty()) {
            return longInterval(previous, current) ? GapReason.LEGACY_UNATTRIBUTED : GapReason.NONE;
        }
        Run first = runs.getFirst();
        if (first.started().isAfter(previousReceipt)) {
            return GapReason.UNCONFIRMED_MONITORING_INTERRUPTION;
        }
        if (runs.size() == 1) {
            if (first.stopped() != null && first.stopped().isBefore(currentReceipt)) {
                return GapReason.UNCONFIRMED_MONITORING_INTERRUPTION;
            }
            return longInterval(previous, current) ? GapReason.RUNNING_DATA_GAP : GapReason.NONE;
        }

        Instant observedThrough = previous;
        boolean runningGap = false;
        for (int i = 0; i < runs.size() - 1; i++) {
            Run run = runs.get(i);
            Instant nextStart = runs.get(i + 1).started();
            if (run.stopped() == null || run.stopped().isBefore(run.started()) || run.stopped().isAfter(nextStart)) {
                return GapReason.UNCONFIRMED_MONITORING_INTERRUPTION;
            }
            if (run.stopped().compareTo(current) >= 0 || nextStart.compareTo(previous) <= 0) {
                continue; // The samples describe a measured interval outside this outage.
            }
            runningGap |= longInterval(observedThrough, run.stopped());
            observedThrough = nextStart;
        }
        Run last = runs.getLast();
        if (last.stopped() != null && last.stopped().isBefore(currentReceipt)) {
            return GapReason.UNCONFIRMED_MONITORING_INTERRUPTION;
        }
        if (observedThrough.equals(previous)) {
            return longInterval(previous, current) ? GapReason.RUNNING_DATA_GAP : GapReason.NONE;
        }
        return runningGap || longInterval(observedThrough, current)
                ? GapReason.MONITORING_INTERRUPTION_WITH_RUNNING_GAP : GapReason.ORDERLY_MONITORING_INTERRUPTION;
    }

    private static boolean longInterval(Instant earlier, Instant later) {
        return Duration.between(earlier, later).compareTo(RiskFeatures.MAX_OBSERVED_INTERVAL) > 0;
    }
}
