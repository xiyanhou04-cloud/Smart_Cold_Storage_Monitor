package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.repository.PredictionRepository;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingAccepted;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Asynchronous work starts only after the ingest transaction commits. */
@Component
public class RiskPredictionWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskPredictionWorker.class);
    private final ThreadPoolTaskExecutor executor;
    private final PredictionRepository predictions;
    private final ReadingRiskService service;

    public RiskPredictionWorker(@Qualifier("riskExecutor") ThreadPoolTaskExecutor riskExecutor, PredictionRepository predictions,
                                ReadingRiskService service) {
        executor = riskExecutor;
        this.predictions = predictions;
        this.service = service;
    }

    @Bean(defaultCandidate = false)
    static ThreadPoolTaskExecutor riskExecutor() {
        var pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(512);
        pool.setThreadNamePrefix("storage-risk-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(10);
        return pool;
    }

    @TransactionalEventListener
    public void afterCommit(ReadingAccepted event) {
        submit(() -> service.predictReading(event.reading()));
    }

    @Override
    public void run(ApplicationArguments args) {
        submit(this::backfill);
    }

    private void submit(Runnable work) {
        try {
            executor.execute(work);
        } catch (TaskRejectedException ex) {
            log.warn("Risk worker unavailable. Pending readings can be recovered on restart.", ex);
        }
    }

    private void backfill() {
        try {
            long session = 0;
            Instant after = null;
            while (!Thread.currentThread().isInterrupted()) {
                var pending = predictions.pendingAfter(session, after, 256);
                if (pending.isEmpty()) {
                    return;
                }
                for (var reading : pending) {
                    service.predictReading(reading);
                    session = reading.sessionId();
                    after = reading.measuredAt();
                }
            }
        } catch (RuntimeException ex) {
            log.error("Could not scan pending storage risk predictions.", ex);
        }
    }
}
