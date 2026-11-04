package com.icu.smart_cold_storage_monitor.service;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Generates random passwords for new accounts and resets when the administrator does not supply one. */
@Component
public class PasswordGenerator {

    private static final int RANDOM_BYTES = 18; // 24 URL-safe Base64 characters, ~144 bits of entropy

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
