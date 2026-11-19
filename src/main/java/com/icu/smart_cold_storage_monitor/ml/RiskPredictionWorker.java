package com.icu.smart_cold_storage_monitor.ml;

import com.icu.smart_cold_storage_monitor.repository.PredictionRepository;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingAccepted;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.sqlite.SQLiteException;

/** Committed database jobs are authoritative. Events only wake the bounded drain. */
@Component
public class RiskPredictionWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RiskPredictionWorker.class);
    private static final int BATCH_SIZE = 64;
    private final ThreadPoolTaskExecutor executor;
    private final ThreadPoolTaskScheduler timer;
    private final Duration pollInterval;
    private final PredictionRepository predictions;
    private final ReadingRiskService service;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean stopping;
    private boolean seeded;
    private ScheduledFuture<?> sweep;

    public RiskPredictionWorker(@Qualifier("riskExecutor") ThreadPoolTaskExecutor riskExecutor, PredictionRepository predictions,
                                ReadingRiskService service,
                                @Qualifier("riskTimer") ThreadPoolTaskScheduler timer,
                                @Value("${app.risk.poll-interval:PT0.25S}") Duration pollInterval) {
        executor = riskExecutor;
        this.predictions = predictions;
        this.service = service;
        this.timer = timer;
        this.pollInterval = pollInterval;
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

    @Bean(defaultCandidate = false)
    static ThreadPoolTaskScheduler riskTimer() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("storage-risk-poll-");
        return scheduler;
    }

    @TransactionalEventListener
    public void afterCommit(ReadingAccepted event) {
        signal();
    }

    @Override
    public void run(ApplicationArguments args) {
        signal();
        sweep = timer.scheduleWithFixedDelay(this::signal, Instant.now().plus(pollInterval), pollInterval);
    }

    @PreDestroy
    void stopPolling() {
        stopping = true;
        if (sweep != null) {
            sweep.cancel(false);
        }
    }

    private void signal() {
        if (stopping || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    drain();
                } finally {
                    scheduled.set(false);
                }
            });
        } catch (TaskRejectedException ex) {
            scheduled.set(false);
            log.debug("Risk worker did not accept a wake-up. Durable jobs will be retried.");
        }
    }

    private void drain() {
        try {
            if (stopping || Thread.currentThread().isInterrupted()) {
                return;
            }
            if (!seeded) {
                predictions.enqueueExisting();
                seeded = true;
            }
            for (var job : predictions.due(System.currentTimeMillis(), BATCH_SIZE)) {
                if (stopping || Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    service.predictReading(job.reading());
                } catch (RuntimeException ex) {
                    if (Thread.currentThread().isInterrupted() || stopping) {
                        return;
                    }
                    boolean terminal = ex instanceof IllegalArgumentException || ex instanceof NoSuchElementException;
                    int attempts = Math.min(job.failedAttempts(), 30) + 1;
                    long delayMs = Math.min(30_000, 1_000L << Math.min(attempts - 1, 5));
                    String code = terminal ? "INVALID_READING_HISTORY"
                            : lockContention(ex) ? "SQLITE_BUSY" : "PREDICTION_RETRY";
                    predictions.recordFailure(job.reading().id(), attempts, System.currentTimeMillis() + delayMs,
                            terminal, code);
                    log.warn("Prediction job {} failed ({}, attempt {}).", job.reading().id(), code, attempts, ex);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Prediction sweep could not finish; durable work will be retried.", ex);
        }
    }

    /**
     * SQLite's write lock was taken. A failed statement reports {@link CannotAcquireLockException}; a transaction
     * that cannot begin ({@code BEGIN IMMEDIATE}) reports Spring's {@code CannotCreateTransactionException} with the
     * driver's BUSY or LOCKED error as its cause.
     */
    static boolean lockContention(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotAcquireLockException) {
                return true;
            }
            if (cause instanceof SQLiteException sqlite) {
                String code = sqlite.getResultCode().name();
                return code.startsWith("SQLITE_BUSY") || code.startsWith("SQLITE_LOCKED");
            }
        }
        return false;
    }
}
