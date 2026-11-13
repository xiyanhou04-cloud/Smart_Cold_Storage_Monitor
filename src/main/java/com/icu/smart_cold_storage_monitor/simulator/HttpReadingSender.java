package com.icu.smart_cold_storage_monitor.simulator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Posts each reading as JSON with the device's credential header. */
final class HttpReadingSender implements ReadingSender {
    private static final int MAX_DETAIL_LENGTH = 200;

    private final HttpClient client;
    private final URI endpoint;
    private final DeviceCredentials credentials;
    private final ObjectMapper mapper;
    private final Duration timeout;

    HttpReadingSender(HttpClient client, URI endpoint, DeviceCredentials credentials,
                      ObjectMapper mapper, Duration timeout) {
        this.client = client;
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    @Override
    public SendResult send(ReadingPayload payload) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", credentials.authorizationHeader(payload.deviceId()))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            return new SendResult(Outcome.RETRYABLE, 0, ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new SendResult(Outcome.STOP_ALL, 0, "INTERRUPTED");
        }
        int status = response.statusCode();
        return new SendResult(outcome(status), status, detail(response.body()));
    }

    static Outcome outcome(int status) {
        if (status == 200 || status == 201) return Outcome.ACCEPTED;
        if (status == 401 || status == 403) return Outcome.STOP_ALL;
        if (status == 404) return Outcome.STOP_DEVICE;
        if (status == 408 || status == 429 || status >= 500) return Outcome.RETRYABLE;
        return Outcome.REJECTED;
    }

    /** Uses the error body's {@code code} when present (the backend's ApiError shape). */
    private String detail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode code = mapper.readTree(body).get("code");
            if (code != null && code.isString()) {
                return code.asString();
            }
        } catch (JacksonException ignored) {
            // Not JSON; fall through to the raw text.
        }
        String text = body.strip().replaceAll("\\s+", " ");
        return text.length() > MAX_DETAIL_LENGTH ? text.substring(0, MAX_DETAIL_LENGTH) + "..." : text;
    }
}
