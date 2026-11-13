package com.icu.smart_cold_storage_monitor.simulator;

import com.icu.smart_cold_storage_monitor.simulator.ReplayLoop.RetryPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Public entry point for running the replay inside another program (the backend starts one at startup).
 * Load the scenario, provision a storage session per device, then {@link #start} the loop on a daemon
 * thread that posts to the reading endpoint until {@link #stop}. Plain Java, no Spring.
 */
public final class ScenarioReplay {
    private static final long STOP_WAIT_MS = 5000;

    private final Scenario scenario;
    private ReplayLoop loop;
    private Thread thread;

    private ScenarioReplay(Scenario scenario) {
        this.scenario = scenario;
    }

    /**
     * Loads and checks the scenario file.
     *
     * @param devices devices to replay; empty means every device in the file
     * @throws IllegalArgumentException if the file content is invalid or a device is missing
     */
    public static ScenarioReplay load(Path data, Set<String> devices) throws IOException {
        return new ScenarioReplay(new ScenarioLoader(JsonMapper.builder().build()).load(data, devices));
    }

    /** Device id to the location code of its first sample (null if the file has none), in device order. */
    public Map<String, String> deviceLocations() {
        Map<String, String> locations = new LinkedHashMap<>();
        for (String device : scenario.devices()) {
            locations.put(device, scenario.samples(device).getFirst().locationCode());
        }
        return locations;
    }

    /**
     * Starts replaying forever on a daemon thread named {@code sensor-simulator}.
     *
     * @param sessions   storage session id for every replayed device
     * @param secret     device secret shared with the receiver (at least 16 characters)
     * @param readingLog receives one line per accepted reading
     * @param noticeLog  receives lines for readings that were not accepted, skipped ticks, and the final summary
     */
    public synchronized void start(URI endpoint, String secret, Map<String, Long> sessions, Duration interval,
                                   Consumer<String> readingLog, Consumer<String> noticeLog) {
        if (thread != null) {
            throw new IllegalStateException("The replay is already running.");
        }
        if (!sessions.keySet().containsAll(scenario.devices())) {
            throw new IllegalArgumentException("Every replayed device needs a storage session.");
        }
        HttpReadingSender sender = new HttpReadingSender(
            HttpClient.newBuilder().connectTimeout(SimulatorMain.REQUEST_TIMEOUT).build(), endpoint,
            new DeviceCredentials(secret), JsonMapper.builder().build(), SimulatorMain.REQUEST_TIMEOUT);
        RetryPolicy retry = SimulatorMain.RETRY;
        loop = new ReplayLoop(scenario, sessions, sender, Clock.systemUTC(), retry, 0, readingLog, noticeLog,
            TimeUnit.NANOSECONDS::sleep, System::nanoTime);
        ReplayLoop running = loop;
        thread = new Thread(() -> {
            try {
                running.run(interval);
            } finally {
                running.printSummary();
            }
        }, "sensor-simulator");
        thread.setDaemon(true);
        thread.start();
    }

    /** True while the loop thread is running (it ends on {@link #stop} or when every device has stopped). */
    public synchronized boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    /** Stops the loop after the current send and waits briefly for the thread to end. */
    public synchronized void stop() {
        if (thread == null) {
            return;
        }
        loop.requestStop();
        thread.interrupt();
        try {
            thread.join(STOP_WAIT_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
