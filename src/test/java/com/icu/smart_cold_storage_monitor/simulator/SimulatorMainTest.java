package com.icu.smart_cold_storage_monitor.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SimulatorMainTest {
    final ObjectMapper mapper = JsonMapper.builder().build();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @TempDir
    Path dir;

    int run(Map<String, String> env, String... args) {
        return SimulatorMain.run(args, env,
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void httpModeReplaysAndWrapsWithSendTimeStamps() throws IOException {
        // Two samples per device, one cycle at a one-second interval: two ticks, four POSTs.
        Path data = dir.resolve("two.jsonl");
        List<String> lines = Files.readAllLines(Path.of("data/monitoring_timestamped.jsonl")).stream()
            .filter(l -> l.contains("\"sequence_number\": 0,") || l.contains("\"sequence_number\": 1,"))
            .filter(l -> l.contains("ESP32-SIM-001") || l.contains("ESP32-SIM-002"))
            .toList();
        Files.write(data, lines);
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/readings", exchange -> {
            received.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        Instant before = Instant.now();
        int code;
        try {
            code = run(Map.of("APP_DEVICE_SECRET", "0123456789abcdef"), "--mode=http",
                "--data=" + data, "--url=http://127.0.0.1:" + server.getAddress().getPort() + "/api/readings",
                "--sessions=ESP32-SIM-001=1,ESP32-SIM-002=2", "--interval=1", "--cycles=1");
        } finally {
            server.stop(0);
        }

        assertThat(code).isZero();
        assertThat(received).hasSize(4);
        assertThat(received).extracting(n -> n.get("deviceId").asString())
            .containsExactly("ESP32-SIM-001", "ESP32-SIM-002", "ESP32-SIM-001", "ESP32-SIM-002");
        assertThat(received).extracting(n -> n.get("sessionId").asLong()).containsExactly(1L, 2L, 1L, 2L);
        List<Instant> stamps = received.stream().map(n -> Instant.parse(n.get("timestamp").asString())).toList();
        assertThat(stamps.getFirst()).isAfterOrEqualTo(before.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(stamps.get(2)).isAfter(stamps.get(0));
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("-> 201", "finished");
    }

    @Test
    void dryRunPrintsThePayload() throws IOException {
        Path data = dir.resolve("one.jsonl");
        Files.write(data, Files.readAllLines(Path.of("data/monitoring_timestamped.jsonl")).subList(0, 1));

        int code = run(Map.of(), "--data=" + data, "--cycles=1");

        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
            .contains("cycle 1 [00:00] ESP32-SIM-001 RAW-MEAT")
            .contains("DRY_RUN {")
            .contains("\"deviceId\":\"ESP32-SIM-001\"")
            .contains("\"sessionId\":null");
    }

    @Test
    void usageErrorExitsWithTwo() {
        assertThat(run(Map.of(), "--interval=99")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("between 1 and 30", "Usage:");
    }

    @Test
    void missingSessionInHttpModeExitsWithTwo() {
        assertThat(run(Map.of("APP_DEVICE_SECRET", "0123456789abcdef"), "--mode=http",
            "--devices=ESP32-SIM-001")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--sessions for: ESP32-SIM-001");
    }

    @Test
    void missingDataFileExitsWithOne() {
        assertThat(run(Map.of(), "--data=" + dir.resolve("absent.jsonl"))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Scenario file not found");
    }

    @Test
    void helpExitsWithZero() {
        assertThat(run(Map.of(), "--help")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Usage:");
    }
}
