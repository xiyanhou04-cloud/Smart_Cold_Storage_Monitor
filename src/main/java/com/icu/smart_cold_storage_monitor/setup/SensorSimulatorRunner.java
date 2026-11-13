package com.icu.smart_cold_storage_monitor.setup;

import com.icu.smart_cold_storage_monitor.config.DeviceIngestionSecurityConfig;
import com.icu.smart_cold_storage_monitor.config.SimulatorProperties;
import com.icu.smart_cold_storage_monitor.model.StorageSession;
import com.icu.smart_cold_storage_monitor.security.DeviceCredentialService;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.simulator.ScenarioReplay;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Starts the built-in sensor simulator once the HTTP server is ready, whether or not anyone is signed in.
 *
 * <p>For each device in the scenario file it registers the device and makes sure it has an active storage session
 * (started now if missing, audited as STORAGE_SESSION_STARTED). It then replays the recorded samples forever on a
 * background thread, posting each reading with the actual send time to this server's own
 * {@code POST /api/readings} over HTTP with the device credential, so the readings take the same path as any
 * external device. Accepted readings are logged at DEBUG; rejected ones at WARN.
 *
 * <p>With {@code APP_SIMULATOR_ENABLED=false} the devices and sessions are still provisioned, so an external
 * simulator (sharing {@code APP_DEVICE_SECRET}) can post, but no loop starts. A missing or invalid scenario file is
 * logged and the application keeps running without the simulator. Nothing happens without a running HTTP server
 * (mock web tests).
 */
@Component
public class SensorSimulatorRunner implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SensorSimulatorRunner.class);
    private static final Logger readingLog = LoggerFactory.getLogger("com.icu.smart_cold_storage_monitor.simulator");

    private final SimulatorProperties properties;
    private final DeviceSessionService deviceSessions;
    private final DeviceCredentialService credentials;
    private final Environment environment;
    private volatile ScenarioReplay replay;

    public SensorSimulatorRunner(SimulatorProperties properties, DeviceSessionService deviceSessions,
                                 DeviceCredentialService credentials, Environment environment) {
        this.properties = properties;
        this.deviceSessions = deviceSessions;
        this.credentials = credentials;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        String port = environment.getProperty("local.server.port");
        if (port == null) {
            log.info("Sensor simulator not started: no HTTP server is running (for example in a mock web test).");
            return;
        }
        ScenarioReplay loaded;
        try {
            loaded = ScenarioReplay.load(Path.of(properties.data()), new LinkedHashSet<>(properties.devices()));
        } catch (IOException | IllegalArgumentException ex) {
            log.error("Sensor simulator not started: cannot load scenario {}: {}", properties.data(), ex.getMessage());
            return;
        }
        Map<String, Long> sessions = new LinkedHashMap<>();
        loaded.deviceLocations().forEach((device, location) -> {
            String productType = location == null ? "UNSPECIFIED" : location;
            StorageSession session = deviceSessions.ensureDeviceWithActiveSession(
                    device, "Simulated " + productType + " sensor", location, productType);
            sessions.put(device, session.id());
        });
        URI endpoint = URI.create("http://" + host() + ":" + port + DeviceIngestionSecurityConfig.READINGS_URL);
        if (!properties.enabled()) {
            log.info("Built-in sensor simulator is disabled; devices and active sessions are ready for an external "
                    + "simulator at {} (sessions {}).", endpoint, sessions);
            return;
        }
        loaded.start(endpoint, credentials.secret(), sessions, properties.interval(),
                readingLog::debug, readingLog::warn);
        replay = loaded;
        log.info("Sensor simulator started: {} device(s) from {} every {} s to {} (sessions {}).",
                sessions.size(), properties.data(), properties.interval().toSeconds(), endpoint, sessions);
    }

    /** True while the background replay thread is running. */
    public boolean isRunning() {
        ScenarioReplay current = replay;
        return current != null && current.isRunning();
    }

    @Override
    public void destroy() {
        ScenarioReplay current = replay;
        if (current != null) {
            current.stop();
        }
    }

    /** The configured bind address, or loopback when the server listens on every interface. */
    private String host() {
        String address = environment.getProperty("server.address");
        if (address == null || address.isBlank() || address.equals("0.0.0.0") || address.equals("::")) {
            return "127.0.0.1";
        }
        return address.contains(":") ? "[" + address + "]" : address;
    }
}
