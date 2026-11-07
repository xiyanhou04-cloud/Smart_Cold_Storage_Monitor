package com.icu.smart_cold_storage_monitor.monitoring;

import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("monitoring-live")
class LiveTelemetryController {
    private final LiveTelemetryService service;
    private final byte[] secret;

    LiveTelemetryController(LiveTelemetryService service,
                            @Value("${app.telemetry.device-secret}") String secret) {
        if (secret.length() < 16) {
            throw new IllegalArgumentException("Set APP_TELEMETRY_DEVICE_SECRET to at least 16 characters.");
        }
        this.service = service;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/api/telemetry/readings")
    ResponseEntity<?> ingest(@RequestHeader(value = "X-Device-Id", defaultValue = "") String device,
                             @RequestHeader(value = "X-Device-Token", defaultValue = "") String token,
                             @RequestBody Submission submission) {
        if (device.length() > 128 || token.length() != 64 || !service.knowsDevice(device)
                || !MessageDigest.isEqual(deviceToken(device), token.getBytes(StandardCharsets.US_ASCII))) {
            return ResponseEntity.status(401).body(new Rejection("INVALID_DEVICE_CREDENTIAL"));
        }
        LiveTelemetryService.WriteResult result = service.ingest(device, submission);
        return switch (result) {
            case CREATED -> ResponseEntity.status(201).body(new Receipt("CREATED"));
            case DUPLICATE -> ResponseEntity.ok(new Receipt("DUPLICATE"));
            case CONFLICT -> ResponseEntity.status(409).body(new Rejection("SAMPLE_IDENTITY_CONFLICT"));
        };
    }

    @GetMapping("/api/monitoring/alerts")
    List<LiveTelemetryService.Alert> alerts(@RequestParam(defaultValue = "ALL") String state,
                                            @RequestParam(defaultValue = "100") int limit) {
        if (!List.of("ALL", "OPEN", "CLOSED").contains(state)) {
            throw new InvalidInputException("state", "Expected ALL, OPEN or CLOSED.");
        }
        return service.alerts(state, checkLimit(limit));
    }

    @GetMapping("/api/monitoring/alert-events")
    List<LiveTelemetryService.AlertEvent> events(@RequestParam(defaultValue = "0") long afterId,
                                                  @RequestParam(defaultValue = "100") int limit) {
        if (afterId < 0) throw new InvalidInputException("afterId", "Must be nonnegative.");
        return service.events(afterId, checkLimit(limit));
    }

    @GetMapping("/api/telemetry/samples")
    List<LiveTelemetryService.Sample> samples(@RequestParam String bootId,
                                              @RequestParam(defaultValue = "100") int limit) {
        return service.samples(bootId, checkLimit(limit));
    }

    private static int checkLimit(int limit) {
        if (limit < 1 || limit > 500) throw new InvalidInputException("limit", "Expected 1 to 500.");
        return limit;
    }

    private byte[] deviceToken(String device) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(device.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Device authentication is unavailable.", ex);
        }
    }

    record Submission(String bootId, Long sequence, Instant measuredAt, Boolean synthetic,
                      Channel temperature, Channel humidity, String rawFrameHex) {}
    record Channel(Double value, String state) {}
    record Receipt(String status) {}
    record Rejection(String code) {}
}
