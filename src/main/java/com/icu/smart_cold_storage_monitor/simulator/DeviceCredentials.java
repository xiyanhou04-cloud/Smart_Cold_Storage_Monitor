package com.icu.smart_cold_storage_monitor.simulator;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives each device's token as the lowercase hex HMAC-SHA256 of its device id under one shared
 * secret (the same scheme as the monitoring prototype). Sent as
 * {@code Authorization: Device <deviceId>:<token>}, which {@code POST /api/readings} verifies.
 * Never log the secret or a token.
 */
final class DeviceCredentials {
    static final int MIN_SECRET_LENGTH = 16;

    private final SecretKeySpec key;

    DeviceCredentials(String secret) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                "The device secret must be at least " + MIN_SECRET_LENGTH + " characters.");
        }
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    String token(String deviceId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(deviceId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", ex);
        }
    }

    String authorizationHeader(String deviceId) {
        return "Device " + deviceId + ":" + token(deviceId);
    }

    @Override
    public String toString() {
        return "DeviceCredentials[secret hidden]";
    }
}
