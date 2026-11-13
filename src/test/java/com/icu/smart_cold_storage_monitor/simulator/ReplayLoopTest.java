package com.icu.smart_cold_storage_monitor.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.simulator.ReadingSender.Outcome;
import com.icu.smart_cold_storage_monitor.simulator.ReadingSender.SendResult;
import com.icu.smart_cold_storage_monitor.simulator.ReplayLoop.DeviceSummary;
import com.icu.smart_cold_storage_monitor.simulator.ReplayLoop.RetryPolicy;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ReplayLoopTest {
    static final Instant START = Instant.parse("2026-11-12T08:00:00.123456Z");

    final MutableClock clock = new MutableClock(START);
    final RecordingSender sender = new RecordingSender();
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final List<Long> sleeps = new ArrayList<>();
    long fakeNanos;

    // Two devices with three samples each: temperatures 1,2,3 and 11,12,13.
    final Scenario scenario = new Scenario(scenarioMap());

    ReplayLoop loop(long cycles) {
        return loop(cycles, Map.of("A", 10L, "B", 20L));
    }

    ReplayLoop loop(long cycles, Map<String, Long> sessions) {
        return new ReplayLoop(scenario, sessions, sender, clock, new RetryPolicy(3, Duration.ofMillis(100)),
            cycles, new PrintStream(output, true, StandardCharsets.UTF_8),
            nanos -> { sleeps.add(nanos); fakeNanos += nanos; }, () -> fakeNanos);
    }

    @Test
    void stampsEachTickWithTheCurrentTimeTruncatedToMillis() {
        ReplayLoop loop = loop(0);

        loop.tick();
        clock.advance(Duration.ofSeconds(5));
        loop.tick();

        assertThat(sender.payloads).extracting(ReadingPayload::timestamp).containsExactly(
            "2026-11-12T08:00:00.123Z", "2026-11-12T08:00:00.123Z",
            "2026-11-12T08:00:05.123Z", "2026-11-12T08:00:05.123Z");
    }

    @Test
    void sendsDeviceSamplesInOrderWithTheirSessions() {
        ReplayLoop loop = loop(0);

        loop.tick();
        loop.tick();

        assertThat(sender.payloads).extracting(ReadingPayload::deviceId, ReadingPayload::sessionId,
                ReadingPayload::temperatureC)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("A", 10L, 1.0),
                org.assertj.core.groups.Tuple.tuple("B", 20L, 11.0),
                org.assertj.core.groups.Tuple.tuple("A", 10L, 2.0),
                org.assertj.core.groups.Tuple.tuple("B", 20L, 12.0));
    }

    @Test
    void wrapsToTheFirstSampleAndCountsCycles() {
        ReplayLoop loop = loop(0);

        for (int i = 0; i < 4; i++) {
            loop.tick();
        }

        assertThat(sender.payloads.stream().filter(p -> p.deviceId().equals("A")))
            .extracting(ReadingPayload::temperatureC).containsExactly(1.0, 2.0, 3.0, 1.0);
        assertThat(loop.summary()).extracting(DeviceSummary::cycle).containsOnly(2L);
        assertThat(loop.hasActiveDevices()).isTrue();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("cycle 2 [00:00] A");
    }

    @Test
    void stopsAfterTheRequestedNumberOfCycles() {
        ReplayLoop loop = loop(2);

        loop.run(Duration.ofSeconds(5));

        assertThat(sender.payloads).hasSize(2 * 3 * 2);
        assertThat(loop.summary()).extracting(DeviceSummary::state).containsOnly("finished");
        // Five sleeps between six ticks, each one full interval because sending took no time.
        assertThat(sleeps).hasSize(5).containsOnly(Duration.ofSeconds(5).toNanos());
    }

    @Test
    void retriesTransientFailureWithTheIdenticalPayload() {
        sender.script("A", Outcome.RETRYABLE, Outcome.RETRYABLE, Outcome.ACCEPTED);
        ReplayLoop loop = loop(0);

        loop.tick();

        List<ReadingPayload> forA = sender.payloads.stream().filter(p -> p.deviceId().equals("A")).toList();
        assertThat(forA).hasSize(3).containsOnly(forA.getFirst());
        assertThat(sleeps).containsExactly(Duration.ofMillis(100).toNanos(), Duration.ofMillis(100).toNanos());
        assertThat(loop.summary().getFirst().accepted()).isEqualTo(1);
    }

    @Test
    void dropsTheSampleAfterRetriesAreExhausted() {
        sender.script("A", Outcome.RETRYABLE, Outcome.RETRYABLE, Outcome.RETRYABLE);
        ReplayLoop loop = loop(0);

        loop.tick();
        loop.tick();

        List<ReadingPayload> forA = sender.payloads.stream().filter(p -> p.deviceId().equals("A")).toList();
        assertThat(forA).extracting(ReadingPayload::temperatureC).containsExactly(1.0, 1.0, 1.0, 2.0);
        assertThat(loop.summary().getFirst().failed()).isEqualTo(1);
    }

    @Test
    void rejectedReadingIsCountedAndTheLoopContinues() {
        sender.script("A", Outcome.REJECTED);
        ReplayLoop loop = loop(0);

        loop.tick();
        loop.tick();

        assertThat(loop.summary().getFirst().rejected()).isEqualTo(1);
        assertThat(loop.summary().getFirst().accepted()).isEqualTo(1);
    }

    @Test
    void stopDeviceStopsOnlyThatDevice() {
        sender.script("A", Outcome.STOP_DEVICE);
        ReplayLoop loop = loop(0);

        loop.tick();
        loop.tick();

        assertThat(sender.payloads).extracting(ReadingPayload::deviceId).containsExactly("A", "B", "B");
        assertThat(loop.summary().getFirst().state()).startsWith("stopped");
        assertThat(loop.hasActiveDevices()).isTrue();
    }

    @Test
    void stopAllEndsTheRun() {
        sender.script("A", Outcome.STOP_ALL);
        ReplayLoop loop = loop(0);

        loop.run(Duration.ofSeconds(5));

        assertThat(sender.payloads).extracting(ReadingPayload::deviceId).containsExactly("A");
        assertThat(loop.hasActiveDevices()).isFalse();
        assertThat(loop.summary()).extracting(DeviceSummary::state).allMatch(s -> s.startsWith("stopped"));
    }

    @Test
    void lateTickSkipsMissedTicksInsteadOfBursting() {
        // Each tick takes 12 s of monotonic time, so two 5 s ticks are missed after each of the
        // first two ticks; the third tick finishes the single cycle.
        sender.onSend = p -> { if (p.deviceId().equals("A")) fakeNanos += Duration.ofSeconds(12).toNanos(); return null; };
        ReplayLoop loop = loop(1);

        loop.run(Duration.ofSeconds(5));

        assertThat(sender.payloads).hasSize(3 * 2);
        assertThat(loop.skippedTicks()).isEqualTo(4);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3).toNanos(), Duration.ofSeconds(3).toNanos());
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("skipping them");
    }

    @Test
    void missingSessionIsSentAsNull() {
        ReplayLoop loop = loop(0, Map.of());

        loop.tick();

        assertThat(sender.payloads).extracting(ReadingPayload::sessionId).containsOnlyNulls();
    }

    @Test
    void requestStopEndsTheRunAfterTheCurrentTick() {
        ReplayLoop loop = loop(0);
        sender.onSend = p -> { loop.requestStop(); return null; };

        loop.run(Duration.ofSeconds(5));

        assertThat(sender.payloads).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    private static Map<String, List<SamplePoint>> scenarioMap() {
        Map<String, List<SamplePoint>> map = new LinkedHashMap<>();
        map.put("A", samples("A", 1));
        map.put("B", samples("B", 11));
        return map;
    }

    private static List<SamplePoint> samples(String device, double base) {
        return List.of(
            new SamplePoint(device, "Z-" + device, 0, 0, base, 50),
            new SamplePoint(device, "Z-" + device, 1, 5, base + 1, 50),
            new SamplePoint(device, "Z-" + device, 2, 10, base + 2, 50));
    }

    static final class RecordingSender implements ReadingSender {
        final List<ReadingPayload> payloads = new ArrayList<>();
        final Map<String, Deque<Outcome>> scripts = new LinkedHashMap<>();
        Function<ReadingPayload, Void> onSend = p -> null;

        void script(String device, Outcome... outcomes) {
            scripts.put(device, new ArrayDeque<>(List.of(outcomes)));
        }

        @Override
        public SendResult send(ReadingPayload payload) {
            payloads.add(payload);
            onSend.apply(payload);
            Deque<Outcome> script = scripts.get(payload.deviceId());
            Outcome outcome = script == null || script.isEmpty() ? Outcome.ACCEPTED : script.poll();
            return new SendResult(outcome, outcome == Outcome.ACCEPTED ? 201 : 500, outcome.name());
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
