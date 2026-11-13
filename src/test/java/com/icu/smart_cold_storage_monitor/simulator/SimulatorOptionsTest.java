package com.icu.smart_cold_storage_monitor.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.simulator.SimulatorOptions.Mode;
import com.icu.smart_cold_storage_monitor.simulator.SimulatorOptions.UsageException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimulatorOptionsTest {
    static final Map<String, String> SECRET_ENV = Map.of("APP_DEVICE_SECRET", "0123456789abcdef");

    static SimulatorOptions parse(Map<String, String> env, String... args) {
        return SimulatorOptions.parse(args, env);
    }

    @Test
    void defaultsToDryRunOverTheDataCopyEveryFiveSecondsForever() {
        SimulatorOptions options = parse(Map.of());

        assertThat(options.mode()).isEqualTo(Mode.DRY_RUN);
        assertThat(options.data()).isEqualTo(Path.of("data/monitoring_timestamped.jsonl"));
        assertThat(options.url()).isEqualTo(URI.create("http://127.0.0.1:8080/api/readings"));
        assertThat(options.devices()).isEmpty();
        assertThat(options.interval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(options.cycles()).isZero();
        assertThat(options.deviceSecret()).isNull();
    }

    @Test
    void parsesEveryOption() {
        SimulatorOptions options = parse(SECRET_ENV, "--mode=http", "--data=x.jsonl", "--url=http://localhost:9000/r",
            "--devices=A, B", "--sessions=A=1,B=2", "--interval=2", "--cycles=3");

        assertThat(options.mode()).isEqualTo(Mode.HTTP);
        assertThat(options.data()).isEqualTo(Path.of("x.jsonl"));
        assertThat(options.url()).isEqualTo(URI.create("http://localhost:9000/r"));
        assertThat(options.devices()).containsExactlyInAnyOrder("A", "B");
        assertThat(options.sessions()).containsExactlyInAnyOrderEntriesOf(Map.of("A", 1L, "B", 2L));
        assertThat(options.interval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(options.cycles()).isEqualTo(3);
        assertThat(options.deviceSecret()).isEqualTo("0123456789abcdef");
    }

    @Test
    void helpReturnsNull() {
        assertThat(parse(Map.of(), "--help")).isNull();
    }

    @Test
    void httpModeRequiresTheSecretFromTheEnvironment() {
        assertThatThrownBy(() -> parse(Map.of(), "--mode=http"))
            .isInstanceOf(UsageException.class).hasMessageContaining("APP_DEVICE_SECRET");
        assertThatThrownBy(() -> parse(Map.of("APP_DEVICE_SECRET", "short"), "--mode=http"))
            .isInstanceOf(UsageException.class).hasMessageContaining("16");
    }

    @Test
    void httpModeRequiresASessionForEveryReplayedDevice() {
        SimulatorOptions options = parse(SECRET_ENV, "--mode=http", "--sessions=A=1");

        assertThatThrownBy(() -> options.checkSessionsFor(Set.of("A", "B")))
            .isInstanceOf(UsageException.class).hasMessageContaining("B");
        options.checkSessionsFor(Set.of("A"));
        parse(Map.of()).checkSessionsFor(Set.of("A", "B"));
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> parse(Map.of(), "--interval=0")).hasMessageContaining("between 1 and 30");
        assertThatThrownBy(() -> parse(Map.of(), "--interval=31")).hasMessageContaining("between 1 and 30");
        assertThatThrownBy(() -> parse(Map.of(), "--interval=fast")).hasMessageContaining("whole number");
        assertThatThrownBy(() -> parse(Map.of(), "--cycles=-1")).hasMessageContaining("cycles");
        assertThatThrownBy(() -> parse(Map.of(), "--mode=tcp")).hasMessageContaining("dry-run or http");
        assertThatThrownBy(() -> parse(Map.of(), "--url=ftp://x")).hasMessageContaining("http or https");
        assertThatThrownBy(() -> parse(Map.of(), "--sessions=A")).hasMessageContaining("DEVICE=ID");
        assertThatThrownBy(() -> parse(Map.of(), "--sessions=A=0")).hasMessageContaining("positive");
        assertThatThrownBy(() -> parse(Map.of(), "--sessions=A=1,A=2")).hasMessageContaining("twice");
        assertThatThrownBy(() -> parse(Map.of(), "--devices=,")).hasMessageContaining("at least one");
        assertThatThrownBy(() -> parse(Map.of(), "--verbose")).hasMessageContaining("Unrecognized");
        assertThatThrownBy(() -> parse(Map.of(), "--speed=2")).hasMessageContaining("Unknown option");
    }

    @Test
    void toStringNeverShowsTheSecret() {
        assertThat(parse(SECRET_ENV, "--mode=http").toString()).doesNotContain("0123456789abcdef");
    }
}
