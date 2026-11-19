package com.icu.smart_cold_storage_monitor.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.icu.smart_cold_storage_monitor.SmartColdStorageMonitorApplication;
import com.icu.smart_cold_storage_monitor.model.Reading;
import com.icu.smart_cold_storage_monitor.repository.PredictionRepository;
import com.icu.smart_cold_storage_monitor.repository.ReadingRepository;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingAccepted;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingSubmission;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class RiskPredictionPressureTest {

    private static final String DEVICE = "pressure-sensor";
    @TempDir Path directory;

    @Test
    void fullQueueAndInterruptedWorkerRecoverSixHundredCommittedReadings() throws Exception {
        try (var app = start()) {
            var session = session(app);
            var jdbc = app.getBean(JdbcClient.class);
            var executor = executor(app);
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            var blocked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var thread = new AtomicReference<Thread>();
            executor.execute(() -> {
                thread.set(Thread.currentThread());
                blocked.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            var queue = executor.getThreadPoolExecutor().getQueue();
            int fill = 0;
            int committed = 0;
            long inputStarted;
            long inputMillis;
            long recoveringAt;
            Reading last = null;
            try {
                while (queue.offer(() -> {})) {
                    fill++;
                }
                assertThat(queue.remainingCapacity()).isZero();
                inputStarted = System.nanoTime();
                for (int n = 0; n < 600; n++) {
                    var result = app.getBean(ReadingIngestionService.class).ingest(DEVICE,
                            new ReadingSubmission(DEVICE, session.id(), session.startedAt().plusMillis(n * 50L), 5, 70));
                    assertThat(result.created()).isTrue();
                    last = result.reading();
                    committed++;
                }
                inputMillis = elapsedMillis(inputStarted);
                assertThat(count(jdbc, "readings")).isEqualTo(committed);
                assertThat(count(jdbc, "prediction_jobs")).isEqualTo(committed);
                assertThat(count(jdbc, "predictions")).isZero();
                assertThat(count(jdbc, "alerts")).isOne();
                assertThat(thresholdAlerts(jdbc)).isOne();
                recoveringAt = System.nanoTime();
                thread.get().interrupt();
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }

            await().atMost(Duration.ofSeconds(25)).until(() -> count(jdbc, "predictions") == 600);
            long recoveryMillis = elapsedMillis(recoveringAt);
            var worker = app.getBean(RiskPredictionWorker.class);
            worker.afterCommit(new ReadingAccepted(last));
            worker.afterCommit(new ReadingAccepted(last));
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertThat(count(jdbc, "prediction_jobs")).isZero();
            assertThat(jdbc.sql("SELECT count(*) - count(DISTINCT reading_id) FROM predictions")
                    .query(Long.class).single()).isZero();
            // 600 out-of-range readings within 30 s: one threshold alert and at most one risk alert per severity.
            assertThat(thresholdAlerts(jdbc)).isOne();
            assertThat(count(jdbc, "alerts WHERE kind = 'RISK'")).isEqualTo(raisedSeverities(jdbc));
            assertThat(duplicateRiskAlerts(jdbc)).isZero();
            System.out.printf("R2 saturation: queueFilled=%d submitted=%d predictions=600 pending=0 duplicates=0 "
                    + "ingestFailures=0 inputMs=%d recoveryMs=%d%n", fill, committed, inputMillis, recoveryMillis);
        }
    }

    @Test
    void sqliteWriterLockRetriesOnlineWithoutRepeatingThresholdAlert() throws Exception {
        var busy = new CountDownLatch(1);
        var allowRetryRecord = new CountDownLatch(1);
        var builder = new SpringApplicationBuilder(SmartColdStorageMonitorApplication.class)
                .initializers(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!(bean instanceof PredictionRepository repository)) {
                            return bean;
                        }
                        var probe = spy(repository);
                        doAnswer(invocation -> {
                            busy.countDown();
                            assertThat(allowRetryRecord.await(5, TimeUnit.SECONDS)).isTrue();
                            return invocation.callRealMethod();
                        }).when(probe).recordFailure(anyLong(), anyInt(), anyLong(), anyBoolean(), eq("SQLITE_BUSY"));
                        return probe;
                    }
                }));
        try (var app = start(builder)) {
            var session = session(app);
            var executor = executor(app);
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            var release = new CountDownLatch(1);
            executor.execute(() -> hold(release));
            var jdbc = app.getBean(JdbcClient.class);
            long id = app.getBean(ReadingIngestionService.class).ingest(DEVICE,
                    new ReadingSubmission(DEVICE, session.id(), session.startedAt(), 5, 70)).reading().id();
            long lockAt = System.nanoTime();
            try (var lock = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("pressure.db"));
                 var sql = lock.createStatement()) {
                sql.execute("BEGIN IMMEDIATE");
                release.countDown();
                assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(count(jdbc, "predictions")).isZero();
                assertThat(count(jdbc, "readings")).isOne();
                assertThat(count(jdbc, "alerts")).isOne();
                sql.execute("COMMIT");
                allowRetryRecord.countDown();
            } finally {
                release.countDown();
                allowRetryRecord.countDown();
            }
            await().atMost(Duration.ofSeconds(4)).until(() -> jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM prediction_jobs WHERE reading_id=? AND failure_code='SQLITE_BUSY')
                    """).param(id).query(Boolean.class).single());
            await().atMost(Duration.ofSeconds(5)).until(() -> count(jdbc, "predictions") == 1);
            assertThat(count(jdbc, "prediction_jobs")).isZero();
            // The retried job opens the risk alert once, in the transaction that stores its prediction.
            assertThat(thresholdAlerts(jdbc)).isOne();
            assertThat(count(jdbc, "alerts WHERE kind = 'RISK'")).isEqualTo(raisedSeverities(jdbc));
            System.out.printf("R2 SQLite busy: submitted=1 predictions=1 pending=0 duplicates=0 "
                    + "recoveredBusyJobs=1 elapsedMs=%d%n", elapsedMillis(lockAt));
        }
    }

    @Test
    void terminalHistoryFailureIsRecordedAndDoesNotBlockOtherSessions() {
        try (var app = start()) {
            var invalidSession = session(app);
            var invalid = app.getBean(ReadingRepository.class).insert(DEVICE, invalidSession.id(),
                    invalidSession.startedAt().minusSeconds(1), invalidSession.startedAt(), 5, 70, null);
            var validSession = app.getBean(DeviceSessionService.class).ensureDeviceWithActiveSession(
                    "healthy-sensor", "test sensor", "RAW-MEAT", "RAW-MEAT");
            var valid = app.getBean(ReadingIngestionService.class).ingest(validSession.deviceId(),
                    new ReadingSubmission(validSession.deviceId(), validSession.id(), validSession.startedAt(), 3, 70))
                    .reading();
            var jdbc = app.getBean(JdbcClient.class);
            await().atMost(Duration.ofSeconds(5)).until(() -> count(jdbc, "predictions") == 1);
            assertThat(jdbc.sql("SELECT reading_id FROM predictions").query(Long.class).single()).isEqualTo(valid.id());
            assertThat(jdbc.sql("""
                    SELECT failure_code FROM prediction_jobs WHERE reading_id=? AND terminal=1 AND failed_attempts=1
                    """).param(invalid.id()).query(String.class).single()).isEqualTo("INVALID_READING_HISTORY");
        }
    }

    @Test
    void shutdownWithActiveDrainKeepsBacklogForOrderedStartupRecovery() throws Exception {
        final int rows = 320;
        var activePrediction = new CountDownLatch(1);
        var releasePrediction = new CountDownLatch(1);
        var shutdownStarted = new CountDownLatch(1);
        var releaseExecutor = new CountDownLatch(1);
        var app = start(new SpringApplicationBuilder(SmartColdStorageMonitorApplication.class)
                .initializers(context -> context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String name) {
                        if (!(bean instanceof ReadingRiskService service)) {
                            return bean;
                        }
                        var instrumented = spy(service);
                        doAnswer(invocation -> {
                            activePrediction.countDown();
                            if (!releasePrediction.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Test did not release prediction.");
                            }
                            return invocation.callRealMethod();
                        }).when(instrumented).predictReading(any());
                        return instrumented;
                    }
                })));
        try {
            app.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    shutdownStarted.countDown();
                }
            });
            var session = session(app);
            var jdbc = app.getBean(JdbcClient.class);
            var executor = executor(app);
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            executor.execute(() -> hold(releaseExecutor));
            var readings = app.getBean(ReadingRepository.class);
            for (int n = rows - 1; n >= 0; n--) {
                var at = session.startedAt().plusMillis(n * 25L);
                readings.insert(DEVICE, session.id(), at, session.startedAt(), 3, 70, null);
            }
            assertThat(count(jdbc, "prediction_jobs")).isEqualTo(rows);
            releaseExecutor.countDown();
            assertThat(activePrediction.await(5, TimeUnit.SECONDS)).isTrue();
            var shutdown = CompletableFuture.runAsync(app::close);
            assertThat(shutdownStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releasePrediction.countDown();
            shutdown.get(15, TimeUnit.SECONDS);
        } finally {
            releaseExecutor.countDown();
            releasePrediction.countDown();
            app.close();
        }

        long pendingAtRestart;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("pressure.db"));
             var statement = connection.createStatement();
             var pending = statement.executeQuery("SELECT count(*) FROM prediction_jobs")) {
            pending.next();
            pendingAtRestart = pending.getLong(1);
            assertThat(pendingAtRestart).isPositive();
        }
        long recoveryAt = System.nanoTime();
        try (var restarted = start()) {
            var jdbc = restarted.getBean(JdbcClient.class);
            await().atMost(Duration.ofSeconds(20)).until(() -> count(jdbc, "predictions") == rows);
            assertThat(count(jdbc, "prediction_jobs")).isZero();
            assertThat(jdbc.sql("""
                    SELECT r.measured_at FROM predictions p JOIN readings r ON r.id=p.reading_id ORDER BY p.id
                    """).query(String.class).list()).isSorted();
            System.out.printf("R2 restart: committed=%d pendingOnRestart=%d predictions=%d pending=0 duplicates=0 "
                    + "ingestFailures=0 recoveryIncludingStartupMs=%d%n", rows, pendingAtRestart, rows, elapsedMillis(recoveryAt));
        }
    }

    private ConfigurableApplicationContext start() {
        return start(new SpringApplicationBuilder(SmartColdStorageMonitorApplication.class));
    }

    private ConfigurableApplicationContext start(SpringApplicationBuilder builder) {
        return builder.run(
                "--server.port=0", "--app.simulator.enabled=false", "--app.database.busy-timeout-ms=100",
                "--app.database.path=" + directory.resolve("pressure.db"));
    }

    private static void hold(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static com.icu.smart_cold_storage_monitor.model.StorageSession session(ConfigurableApplicationContext app) {
        return app.getBean(DeviceSessionService.class).ensureDeviceWithActiveSession(
                DEVICE, "pressure sensor", "RAW-MEAT", "RAW-MEAT");
    }

    private static ThreadPoolTaskExecutor executor(ConfigurableApplicationContext app) {
        return app.getBean("riskExecutor", ThreadPoolTaskExecutor.class);
    }

    private static long count(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static long thresholdAlerts(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) FROM alerts WHERE kind = 'THRESHOLD'").query(Long.class).single();
    }

    /** MEDIUM/HIGH levels among the predictions: within one aggregation window, one risk alert each. */
    private static long raisedSeverities(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(DISTINCT risk_level) FROM predictions WHERE risk_level <> 'LOW'")
                .query(Long.class).single();
    }

    /** Risk alerts of the same severity that a retry or a repeated wake-up would have duplicated. */
    private static long duplicateRiskAlerts(JdbcClient jdbc) {
        return jdbc.sql("SELECT count(*) - count(DISTINCT session_id || severity) FROM alerts WHERE kind = 'RISK'")
                .query(Long.class).single();
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
