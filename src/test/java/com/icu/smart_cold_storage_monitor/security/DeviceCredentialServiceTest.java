package com.icu.smart_cold_storage_monitor.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.config.IngestionProperties;
import org.junit.jupiter.api.Test;

class DeviceCredentialServiceTest {
    static final String SECRET = "0123456789abcdef";
    // python: hmac.new(b"0123456789abcdef", b"ESP32-SIM-001", hashlib.sha256).hexdigest()
    static final String TOKEN = "174a4b7a4d13274bff5f92e1bfd0cec52aedaaa915f45619f18f1841cfd40298";

    final DeviceCredentialService service = new DeviceCredentialService(new IngestionProperties(SECRET, true));

    @Test
    void acceptsTheHeaderTheSimulatorSends() {
        // Same value as simulator.DeviceCredentials#authorizationHeader for this secret and device.
        assertThat(service.verify("Device ESP32-SIM-001:" + TOKEN)).contains("ESP32-SIM-001");
        assertThat(service.authorizationHeader("ESP32-SIM-001")).isEqualTo("Device ESP32-SIM-001:" + TOKEN);
    }

    @Test
    void rejectsMalformedOrWrongCredentials() {
        assertThat(service.verify(null)).isEmpty();
        assertThat(service.verify("")).isEmpty();
        assertThat(service.verify("Bearer ESP32-SIM-001:" + TOKEN)).isEmpty();
        assertThat(service.verify("Device " + TOKEN)).isEmpty();
        assertThat(service.verify("Device :" + TOKEN)).isEmpty();
        assertThat(service.verify("Device ESP32-SIM-001:" + TOKEN.substring(1))).isEmpty();
        assertThat(service.verify("Device ESP32-SIM-002:" + TOKEN)).isEmpty();
        assertThat(service.verify("Device ESP32-SIM-001:" + TOKEN.toUpperCase())).isEmpty();
    }

    @Test
    void blankSecretGeneratesARandomOnePerInstance() {
        DeviceCredentialService first = new DeviceCredentialService(new IngestionProperties("", true));
        DeviceCredentialService second = new DeviceCredentialService(new IngestionProperties("", true));

        assertThat(first.secret()).hasSize(64).isNotEqualTo(second.secret());
        assertThat(first.verify(first.authorizationHeader("D"))).contains("D");
        assertThat(second.verify(first.authorizationHeader("D"))).isEmpty();
    }

    @Test
    void shortConfiguredSecretFailsStartup() {
        assertThatThrownBy(() -> new DeviceCredentialService(new IngestionProperties("short", true)))
                .hasMessageContaining("at least 16");
    }

    @Test
    void toStringHidesTheSecret() {
        assertThat(service.toString()).doesNotContain(SECRET);
        assertThat(new IngestionProperties(SECRET, true).toString()).doesNotContain(SECRET);
    }
}
