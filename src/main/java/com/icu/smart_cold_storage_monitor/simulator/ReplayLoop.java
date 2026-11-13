package com.icu.smart_cold_storage_monitor.simulator;

import com.icu.smart_cold_storage_monitor.simulator.ReadingSender.Outcome;
import com.icu.smart_cold_storage_monitor.simulator.ReadingSender.SendResult;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Replays each device's recorded samples in order and wraps to the first sample after the last,
 * stamping every reading with the actual send time. One tick sends one reading per active device,
 * all with the same timestamp. Retries reuse the identical payload, so the receiver can recognize
 * them as retries.
 */
final class ReplayLoop {

    /** Sleeps on the monotonic clock; replaceable in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    /** Retry policy for transient failures within one tick. */
    record RetryPolicy(int maxAttempts, Duration delay) {
        RetryPolicy {
            if (maxAttempts < 1 || delay.isNegative()) {
                throw new IllegalArgumentException("Invalid retry policy.");
            }
        }
    }

    private final List<DeviceState> devices = new ArrayList<>();
    private final Map<String, Long> sessions;
    private final ReadingSender sender;
    private final Clock clock;
    private final RetryPolicy retry;
    private final long cycles;
    private final Consumer<String> readingLog;
    private final Consumer<String> noticeLog;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private volatile boolean stopRequested;
    private long skippedTicks;

    /**
     * @param sessions   storage session id per device; a missing entry sends {@code sessionId: null}
     * @param cycles     number of passes through each device's samples; 0 repeats forever
     * @param readingLog receives one line per accepted reading
     * @param noticeLog  receives lines for readings that were not accepted, skipped ticks, and the summary
     */
    ReplayLoop(Scenario scenario, Map<String, Long> sessions, ReadingSender sender, Clock clock,
               RetryPolicy retry, long cycles, Consumer<String> readingLog, Consumer<String> noticeLog,
               Sleeper sleeper, LongSupplier nanoTime) {
        if (cycles < 0) {
            throw new IllegalArgumentException("cycles must be 0 (forever) or positive.");
        }
        for (String device : scenario.devices()) {
            devices.add(new DeviceState(device, scenario.samples(device)));
        }
        this.sessions = Map.copyOf(sessions);
        this.sender = sender;
        this.clock = clock;
        this.retry = retry;
        this.cycles = cycles;
        this.readingLog = readingLog;
        this.noticeLog = noticeLog;
        this.sleeper = sleeper;
        this.nanoTime = nanoTime;
    }

    /**
     * Sends ticks every {@code interval} until every device has finished or stopped, or until
     * {@link #requestStop()}. A late tick does not trigger a burst: missed ticks are skipped and logged.
     */
    void run(Duration interval) {
        long step = interval.toNanos();
        if (step <= 0) {
            throw new IllegalArgumentException("interval must be positive.");
        }
        long next = nanoTime.getAsLong();
        try {
            while (!stopRequested && hasActiveDevices()) {
                tick();
                if (stopRequested || !hasActiveDevices()) {
                    break;
                }
                next += step;
                long now = nanoTime.getAsLong();
                if (now > next) {
                    long missed = (now - next) / step + 1;
                    skippedTicks += missed;
                    noticeLog.accept(String.format(Locale.ROOT, "Running %d tick(s) behind; skipping them.", missed));
                    next += missed * step;
                }
                sleeper.sleepNanos(next - now);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sends one reading for every active device, then advances each device to its next sample. */
    void tick() {
        String timestamp = ReadingPayload.formatTimestamp(clock.instant());
        for (DeviceState device : devices) {
            if (stopRequested) {
                return;
            }
            if (!device.active()) {
                continue;
            }
            SamplePoint sample = device.current();
            ReadingPayload payload = new ReadingPayload(device.id, sessions.get(device.id), timestamp,
                sample.temperatureC(), sample.humidityPct());
            SendResult result = sendWithRetry(payload);
            log(device, sample, payload, result);
            device.sent++;
            switch (result.outcome()) {
                case ACCEPTED -> device.accepted++;
                case REJECTED -> device.rejected++;
                case RETRYABLE -> device.failed++;
                case STOP_DEVICE -> {
                    device.failed++;
                    device.stopReason = "stopped: " + describe(result);
                }
                case STOP_ALL -> {
                    device.failed++;
                    stopAll("stopped: " + describe(result));
                    return;
                }
            }
            device.advance(cycles);
        }
    }

    private SendResult sendWithRetry(ReadingPayload payload) {
        SendResult result = sender.send(payload);
        for (int attempt = 2; attempt <= retry.maxAttempts() && result.outcome() == Outcome.RETRYABLE; attempt++) {
            try {
                sleeper.sleepNanos(retry.delay().toNanos());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new SendResult(Outcome.STOP_ALL, 0, "INTERRUPTED");
            }
            result = sender.send(payload);
        }
        return result;
    }

    void requestStop() {
        stopRequested = true;
    }

    boolean hasActiveDevices() {
        return devices.stream().anyMatch(DeviceState::active);
    }

    long skippedTicks() {
        return skippedTicks;
    }

    List<DeviceSummary> summary() {
        return devices.stream().map(d -> new DeviceSummary(d.id, d.cycle, d.sent, d.accepted, d.rejected,
            d.failed, d.stopReason != null ? d.stopReason : d.finished ? "finished" : "running")).toList();
    }

    void printSummary() {
        noticeLog.accept("Summary:");
        for (DeviceSummary s : summary()) {
            noticeLog.accept(String.format(Locale.ROOT, "  %s cycle=%d sent=%d accepted=%d rejected=%d failed=%d (%s)",
                s.deviceId(), s.cycle(), s.sent(), s.accepted(), s.rejected(), s.failed(), s.state()));
        }
        if (skippedTicks > 0) {
            noticeLog.accept(String.format(Locale.ROOT, "  skipped ticks: %d", skippedTicks));
        }
    }

    private void stopAll(String reason) {
        for (DeviceState device : devices) {
            if (device.active()) {
                device.stopReason = reason;
            }
        }
    }

    private void log(DeviceState device, SamplePoint sample, ReadingPayload payload, SendResult result) {
        long elapsed = sample.elapsedSeconds();
        String line = String.format(Locale.ROOT, "cycle %d [%02d:%02d] %s %s %s %.2f C %.2f %%RH -> %s",
            device.cycle, elapsed / 60, elapsed % 60, device.id,
            sample.locationCode() == null ? "-" : sample.locationCode(), payload.timestamp(),
            sample.temperatureC(), sample.humidityPct(), describe(result));
        (result.outcome() == Outcome.ACCEPTED ? readingLog : noticeLog).accept(line);
    }

    private static String describe(SendResult result) {
        String status = result.status() > 0 ? result.status() + " " : "";
        return (status + result.detail()).strip();
    }

    record DeviceSummary(String deviceId, long cycle, long sent, long accepted, long rejected,
                         long failed, String state) {
    }

    private static final class DeviceState {
        final String id;
        final List<SamplePoint> samples;
        int index;
        long cycle = 1;
        long sent;
        long accepted;
        long rejected;
        long failed;
        boolean finished;
        String stopReason;

        DeviceState(String id, List<SamplePoint> samples) {
            this.id = id;
            this.samples = samples;
        }

        boolean active() {
            return !finished && stopReason == null;
        }

        SamplePoint current() {
            return samples.get(index);
        }

        void advance(long cycles) {
            if (stopReason != null) {
                return;
            }
            index++;
            if (index == samples.size()) {
                index = 0;
                if (cycles > 0 && cycle == cycles) {
                    finished = true;
                } else {
                    cycle++;
                }
            }
        }
    }
}
