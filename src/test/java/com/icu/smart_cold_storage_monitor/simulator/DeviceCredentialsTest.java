package com.icu.smart_cold_storage_monitor.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeviceCredentialsTest {
    static final String SECRET = "0123456789abcdef";

    @Test
    void matchesHmacSha256ComputedByPython() {
        // python: hmac.new(b"0123456789abcdef", b"ESP32-SIM-001", hashlib.sha256).hexdigest()
        assertThat(new DeviceCredentials(SECRET).token("ESP32-SIM-001"))
            .isEqualTo("174a4b7a4d13274bff5f92e1bfd0cec52aedaaa915f45619f18f1841cfd40298");
    }

    @Test
    void buildsDeviceAuthorizationHeader() {
        assertThat(new DeviceCredentials(SECRET).authorizationHeader("ESP32-SIM-001"))
            .isEqualTo("Device 174a4b7a4d13274bff5f92e1bfd0cec52aedaaa915f45619f18f1841cfd40298");
    }

    @Test
    void differentDevicesGetDifferentTokens() {
        DeviceCredentials credentials = new DeviceCredentials(SECRET);

        assertThat(credentials.token("ESP32-SIM-001")).isNotEqualTo(credentials.token("ESP32-SIM-002"));
    }

    @Test
    void rejectsShortOrMissingSecret() {
        assertThatThrownBy(() -> new DeviceCredentials("short")).hasMessageContaining("at least 16");
        assertThatThrownBy(() -> new DeviceCredentials(null)).hasMessageContaining("at least 16");
    }

    @Test
    void toStringHidesTheSecret() {
        assertThat(new DeviceCredentials(SECRET).toString()).doesNotContain(SECRET);
    }
}
