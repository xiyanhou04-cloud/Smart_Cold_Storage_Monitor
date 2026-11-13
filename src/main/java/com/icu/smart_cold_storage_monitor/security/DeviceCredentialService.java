package com.icu.smart_cold_storage_monitor.security;

import com.icu.smart_cold_storage_monitor.config.IngestionProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies device credentials sent as {@code Authorization: Device <deviceId>:<token>}, where the token is the
 * lowercase hex HMAC-SHA256 of the device id under the shared device secret. The secret comes from
 * {@code app.ingestion.device-secret}; when it is blank a random secret is generated for this run. Neither the
 * secret nor any token is ever logged.
 */
@Component
public class DeviceCredentialService {

    public static final String SCHEME = "Device";
    static final int MIN_SECRET_LENGTH = 16;
    private static final int TOKEN_HEX_LENGTH = 64;
    private static final int MAX_DEVICE_ID_LENGTH = 64;
    private static final Logger log = LoggerFactory.getLogger(DeviceCredentialService.class);

    private final String secret;
    private final SecretKeySpec key;

    public DeviceCredentialService(IngestionProperties properties) {
        String configured = properties.deviceSecret();
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = HexFormat.of().formatHex(random);
            log.info("APP_DEVICE_SECRET is not set; generated a device secret for this run. Only the built-in "
                    + "simulator can post readings; set APP_DEVICE_SECRET to use an external simulator.");
        } else if (configured.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("APP_DEVICE_SECRET must be at least " + MIN_SECRET_LENGTH + " characters.");
        } else {
            secret = configured;
        }
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * Returns the device id if the header carries a well-formed credential with a valid token. It does not check
     * that the device exists or is enabled.
     */
    public Optional<String> verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(SCHEME + " ")) {
            return Optional.empty();
        }
        String credential = authorizationHeader.substring(SCHEME.length() + 1).strip();
        int separator = credential.lastIndexOf(':');
        if (separator <= 0 || separator > MAX_DEVICE_ID_LENGTH) {
            return Optional.empty();
        }
        String deviceId = credential.substring(0, separator);
        String token = credential.substring(separator + 1);
        if (token.length() != TOKEN_HEX_LENGTH) {
            return Optional.empty();
        }
        boolean valid = MessageDigest.isEqual(token(deviceId).getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
        return valid ? Optional.of(deviceId) : Optional.empty();
    }

    /** The {@code Authorization} header value for a device; used by the built-in simulator and tests. */
    public String authorizationHeader(String deviceId) {
        return SCHEME + " " + deviceId + ":" + token(deviceId);
    }

    /** The shared secret, handed to the built-in simulator only. */
    public String secret() {
        return secret;
    }

    private String token(String deviceId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(deviceId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable.", ex);
        }
    }

    @Override
    public String toString() {
        return "DeviceCredentialService[secret hidden]";
    }
}
