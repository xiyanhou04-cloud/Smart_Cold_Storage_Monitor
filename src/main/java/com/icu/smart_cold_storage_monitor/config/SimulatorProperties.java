package com.icu.smart_cold_storage_monitor.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Built-in sensor simulator settings bound from {@code app.simulator.*}.
 *
 * @param enabled  start the replay loop when the server is ready ({@code APP_SIMULATOR_ENABLED}); devices and
 *                 sessions from {@code data} are provisioned either way
 * @param data     scenario JSONL replayed in a loop
 * @param interval time between ticks (one reading per device per tick), 1 to 30 seconds
 * @param devices  devices to replay; empty means every device in the file
 */
@Validated
@ConfigurationProperties(prefix = "app.simulator")
public record SimulatorProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank @DefaultValue("./data/monitoring_timestamped.jsonl") String data,
        @NotNull @DefaultValue("5s") Duration interval,
        @NotNull @DefaultValue List<String> devices) {

    @AssertTrue(message = "app.simulator.interval must be between 1 and 30 seconds")
    public boolean isIntervalInRange() {
        return interval == null
                || interval.compareTo(Duration.ofSeconds(1)) >= 0 && interval.compareTo(Duration.ofSeconds(30)) <= 0;
    }
}
