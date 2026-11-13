package com.icu.smart_cold_storage_monitor.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.simulator.ReadingSender.Outcome;
import com.icu.smart_cold_storage_monitor.simulator.ReadingSender.SendResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class HttpReadingSenderTest {
    final ObjectMapper mapper = JsonMapper.builder().build();
    final DeviceCredentials credentials = new DeviceCredentials("0123456789abcdef");
    final ReadingPayload payload =
        new ReadingPayload("ESP32-SIM-001", 7L, "2026-11-12T08:00:05.123Z", 4.0032, 69.99565);

    HttpServer server;
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> contentType = new AtomicReference<>();
    volatile int status = 201;
    volatile String responseBody = "";
    volatile long delayMs = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/readings", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    HttpReadingSender sender(Duration timeout) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/readings");
        return new HttpReadingSender(HttpClient.newHttpClient(), endpoint, credentials, mapper, timeout);
    }

    @Test
    void postsDesignPayloadWithDeviceCredential() {
        SendResult result = sender(Duration.ofSeconds(2)).send(payload);

        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(result.status()).isEqualTo(201);
        assertThat(authorization.get()).isEqualTo(credentials.authorizationHeader("ESP32-SIM-001"));
        assertThat(contentType.get()).isEqualTo("application/json");
        JsonNode json = mapper.readTree(body.get());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
            "deviceId", "sessionId", "timestamp", "temperatureC", "humidityPct");
        assertThat(json.get("deviceId").asString()).isEqualTo("ESP32-SIM-001");
        assertThat(json.get("sessionId").asLong()).isEqualTo(7);
        assertThat(json.get("timestamp").asString()).isEqualTo("2026-11-12T08:00:05.123Z");
        assertThat(json.get("temperatureC").asDouble()).isEqualTo(4.0032);
        assertThat(json.get("humidityPct").asDouble()).isEqualTo(69.99565);
    }

    @Test
    void reportsErrorCodeFromApiErrorBody() {
        status = 409;
        responseBody = "{\"code\":\"READING_CONFLICT\",\"message\":\"Different values.\"}";

        SendResult result = sender(Duration.ofSeconds(2)).send(payload);

        assertThat(result).isEqualTo(new SendResult(Outcome.REJECTED, 409, "READING_CONFLICT"));
    }

    @Test
    void timeoutIsRetryable() {
        delayMs = 1500;

        SendResult result = sender(Duration.ofMillis(200)).send(payload);

        assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE);
        assertThat(result.status()).isZero();
    }

    @Test
    void refusedConnectionIsRetryable() {
        HttpReadingSender sender = sender(Duration.ofSeconds(2));
        server.stop(0);

        assertThat(sender.send(payload).outcome()).isEqualTo(Outcome.RETRYABLE);
    }

    @Test
    void mapsStatusCodesToOutcomes() {
        assertThat(HttpReadingSender.outcome(200)).isEqualTo(Outcome.ACCEPTED);
        assertThat(HttpReadingSender.outcome(201)).isEqualTo(Outcome.ACCEPTED);
        assertThat(HttpReadingSender.outcome(400)).isEqualTo(Outcome.REJECTED);
        assertThat(HttpReadingSender.outcome(409)).isEqualTo(Outcome.REJECTED);
        assertThat(HttpReadingSender.outcome(401)).isEqualTo(Outcome.STOP_ALL);
        assertThat(HttpReadingSender.outcome(403)).isEqualTo(Outcome.STOP_ALL);
        assertThat(HttpReadingSender.outcome(404)).isEqualTo(Outcome.STOP_DEVICE);
        assertThat(HttpReadingSender.outcome(429)).isEqualTo(Outcome.RETRYABLE);
        assertThat(HttpReadingSender.outcome(503)).isEqualTo(Outcome.RETRYABLE);
    }

    @Test
    void timestampFormatIsFixedWidthUtcMillis() {
        assertThat(ReadingPayload.formatTimestamp(java.time.Instant.parse("2026-11-12T08:00:05Z")))
            .isEqualTo("2026-11-12T08:00:05.000Z");
        assertThat(ReadingPayload.formatTimestamp(java.time.Instant.parse("2026-11-12T08:00:05.123987Z")))
            .isEqualTo("2026-11-12T08:00:05.123Z");
    }
}
