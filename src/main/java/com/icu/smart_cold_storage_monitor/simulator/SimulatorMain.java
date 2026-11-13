package com.icu.smart_cold_storage_monitor.simulator;

import com.icu.smart_cold_storage_monitor.simulator.ReplayLoop.RetryPolicy;
import com.icu.smart_cold_storage_monitor.simulator.SimulatorOptions.Mode;
import com.icu.smart_cold_storage_monitor.simulator.SimulatorOptions.UsageException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Java reading simulator: replays the recorded scenario file in a loop and stamps each reading
 * with its actual send time. Plain Java, no Spring context.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.main-class=com.icu.smart_cold_storage_monitor.simulator.SimulatorMain
 *     -Dspring-boot.run.arguments="--mode=dry-run --devices=ESP32-SIM-001"
 * </pre>
 */
public final class SimulatorMain {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    static final RetryPolicy RETRY = new RetryPolicy(3, Duration.ofMillis(500));
    private static final long SHUTDOWN_WAIT_MS = 3000;
    private static volatile boolean shuttingDown;

    private SimulatorMain() {
    }

    public static void main(String[] args) {
        int code = run(args, System.getenv(), System.out, System.err);
        // During Ctrl+C the JVM is already exiting; System.exit would block on the shutdown hook.
        if (!shuttingDown) {
            System.exit(code);
        }
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        SimulatorOptions options;
        Scenario scenario;
        ObjectMapper mapper = JsonMapper.builder().build();
        try {
            options = SimulatorOptions.parse(args, env);
            if (options == null) {
                out.print(SimulatorOptions.USAGE);
                return 0;
            }
            scenario = new ScenarioLoader(mapper).load(options.data(), options.devices());
            options.checkSessionsFor(scenario.devices());
        } catch (UsageException ex) {
            err.println(ex.getMessage());
            err.print(SimulatorOptions.USAGE);
            return 2;
        } catch (NoSuchFileException ex) {
            err.println("Scenario file not found: " + ex.getFile());
            return 1;
        } catch (IOException | IllegalArgumentException ex) {
            err.println("Cannot load the scenario: " + ex.getMessage());
            return 1;
        }

        ReadingSender sender = options.mode() == Mode.HTTP
            ? new HttpReadingSender(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
                options.url(), new DeviceCredentials(options.deviceSecret()), mapper, REQUEST_TIMEOUT)
            : new ConsoleReadingSender(mapper);
        ReplayLoop loop = new ReplayLoop(scenario, options.sessions(), sender, Clock.systemUTC(), RETRY,
            options.cycles(), out, TimeUnit.NANOSECONDS::sleep,
            System::nanoTime);

        out.printf("Replaying %d device(s) from %s every %d s, %s, mode %s%s.%n",
            scenario.devices().size(), options.data(), options.interval().toSeconds(),
            options.cycles() == 0 ? "forever" : options.cycles() + " cycle(s)",
            options.mode() == Mode.HTTP ? "http" : "dry-run",
            options.mode() == Mode.HTTP ? " to " + options.url() : "");
        out.println("Readings are stamped with the send time; this is a scenario replay, not live sensing.");

        Thread worker = Thread.currentThread();
        Thread hook = new Thread(() -> {
            shuttingDown = true;
            loop.requestStop();
            worker.interrupt();
            try {
                worker.join(SHUTDOWN_WAIT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "simulator-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            loop.run(options.interval());
        } finally {
            loop.printSummary();
            out.flush();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException shuttingDown) {
            // Ctrl+C: the hook is waiting for this thread; just return.
        }
        boolean stoppedAll = loop.summary().stream()
            .allMatch(s -> s.state().startsWith("stopped"));
        return stoppedAll ? 1 : 0;
    }
}
