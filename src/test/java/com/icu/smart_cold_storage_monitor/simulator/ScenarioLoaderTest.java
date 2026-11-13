package com.icu.smart_cold_storage_monitor.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ScenarioLoaderTest {
    static final Path DATA = Path.of("data/monitoring_timestamped.jsonl");
    // Frame 0 of ESP32-SIM-001 decodes to 4.0032 C / 69.99565 %RH.
    static final String FRAME = "47 AF 74 9B A3 79";

    final ScenarioLoader loader = new ScenarioLoader(JsonMapper.builder().build());

    @TempDir
    Path dir;

    @Test
    void loadsAllSevenDevicesFromTheDataCopy() throws IOException {
        Scenario scenario = loader.load(DATA, Set.of());

        assertThat(scenario.devices()).containsExactly("ESP32-SIM-001", "ESP32-SIM-002", "ESP32-SIM-003",
            "ESP32-SIM-004", "ESP32-SIM-005", "ESP32-SIM-006", "ESP32-SIM-007");
        for (String device : scenario.devices()) {
            List<SamplePoint> samples = scenario.samples(device);
            assertThat(samples).hasSize(361);
            assertThat(samples.getFirst().sequence()).isZero();
            assertThat(samples.getLast().elapsedSeconds()).isEqualTo(1800);
        }
        SamplePoint first = scenario.samples("ESP32-SIM-001").getFirst();
        assertThat(first.locationCode()).isEqualTo("RAW-MEAT");
        assertThat(first.temperatureC()).isEqualTo(4.0032);
        assertThat(first.humidityPct()).isEqualTo(69.99565);
    }

    @Test
    void keepsOnlySelectedDevices() throws IOException {
        Scenario scenario = loader.load(DATA, Set.of("ESP32-SIM-001", "ESP32-SIM-007"));

        assertThat(scenario.devices()).containsExactly("ESP32-SIM-001", "ESP32-SIM-007");
    }

    @Test
    void rejectsSelectedDeviceThatIsNotInTheFile() {
        assertThatThrownBy(() -> loader.load(DATA, Set.of("ESP32-SIM-999")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ESP32-SIM-999");
    }

    @Test
    void sortsEachDeviceBySequence() throws IOException {
        Path file = write(line("A", 1, 5, 4.0032, 69.99565), line("A", 0, 0, 4.0032, 69.99565));

        assertThat(loader.load(file, Set.of()).samples("A"))
            .extracting(SamplePoint::sequence).containsExactly(0L, 1L);
    }

    @Test
    void rejectsGapInSequence() throws IOException {
        Path file = write(line("A", 0, 0, 4.0032, 69.99565), line("A", 2, 10, 4.0032, 69.99565));

        assertThatThrownBy(() -> loader.load(file, Set.of()))
            .hasMessageContaining("expected sequence_number 1");
    }

    @Test
    void rejectsStepOtherThanFiveSeconds() throws IOException {
        Path file = write(line("A", 0, 0, 4.0032, 69.99565), line("A", 1, 6, 4.0032, 69.99565));

        assertThatThrownBy(() -> loader.load(file, Set.of()))
            .hasMessageContaining("not 5 s after");
    }

    @Test
    void rejectsValuesThatDoNotMatchTheFrame() throws IOException {
        Path file = write(line("A", 0, 0, 9.5, 69.99565));

        assertThatThrownBy(() -> loader.load(file, Set.of()))
            .hasMessageContaining("line 1")
            .hasMessageContaining("do not match frame_hex");
    }

    @Test
    void rejectsMalformedLineWithItsLineNumber() throws IOException {
        Path file = write(line("A", 0, 0, 4.0032, 69.99565), "{not json");

        assertThatThrownBy(() -> loader.load(file, Set.of()))
            .hasMessageContaining("line 2");
    }

    @Test
    void rejectsMissingDeviceId() throws IOException {
        Path file = write("""
            {"sequence_number":0,"elapsed_seconds":0,"temperatureC":4.0032,"humidityPct":69.99565,"frame_hex":"%s"}
            """.formatted(FRAME).strip());

        assertThatThrownBy(() -> loader.load(file, Set.of()))
            .hasMessageContaining("Missing deviceId");
    }

    @Test
    void rejectsEmptyFile() throws IOException {
        Path file = write("", "  ");

        assertThatThrownBy(() -> loader.load(file, Set.of()))
            .hasMessageContaining("contains no samples");
    }

    private Path write(String... lines) throws IOException {
        Path file = dir.resolve("scenario.jsonl");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static String line(String device, long sequence, long elapsed, double temperature, double humidity) {
        return """
            {"deviceId":"%s","sequence_number":%d,"elapsed_seconds":%d,"temperatureC":%s,"humidityPct":%s,"frame_hex":"%s","location_code":"ZONE"}
            """.formatted(device, sequence, elapsed, temperature, humidity, FRAME).strip();
    }
}
