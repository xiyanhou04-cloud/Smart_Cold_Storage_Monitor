package com.icu.smart_cold_storage_monitor.simulator;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Command-line options. The device secret is read from the environment, never from arguments. */
record SimulatorOptions(Mode mode, Path data, URI url, Set<String> devices, Map<String, Long> sessions,
                        Duration interval, long cycles, String deviceSecret) {

    static final String SECRET_ENV = "APP_DEVICE_SECRET";
    static final Path DEFAULT_DATA = Path.of("data/monitoring_timestamped.jsonl");
    static final URI DEFAULT_URL = URI.create("http://127.0.0.1:8080/api/readings");
    static final long MIN_INTERVAL_SECONDS = 1;
    /** Keep the step within the design's 30 s data-gap limit. */
    static final long MAX_INTERVAL_SECONDS = 30;

    static final String USAGE = """
        Usage: SimulatorMain [options]
          Replays recorded sensor samples in a loop, stamping each reading with its send time.

          --mode=dry-run|http      dry-run prints the JSON (default); http posts it
          --data=PATH              scenario JSONL (default data/monitoring_timestamped.jsonl)
          --url=URL                reading endpoint (default http://127.0.0.1:8080/api/readings)
          --devices=ID[,ID...]     devices to replay (default: every device in the file)
          --sessions=ID=N[,...]    storage session id per device (required for each device in http mode)
          --interval=SECONDS       seconds between ticks, 1 to 30 (default 5)
          --cycles=N               passes through the scenario, 0 = forever (default 0)
          --help                   show this help

          http mode reads the device secret (at least 16 characters) from APP_DEVICE_SECRET.
        """;

    enum Mode { DRY_RUN, HTTP }

    /** Thrown for invalid arguments; the message is meant for the user. */
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /** @return {@code null} when {@code --help} was requested */
    static SimulatorOptions parse(String[] args, Map<String, String> env) {
        Mode mode = Mode.DRY_RUN;
        Path data = DEFAULT_DATA;
        URI url = DEFAULT_URL;
        Set<String> devices = new LinkedHashSet<>();
        Map<String, Long> sessions = new LinkedHashMap<>();
        long intervalSeconds = 5;
        long cycles = 0;
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                return null;
            }
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                throw new UsageException("Unrecognized argument: " + arg);
            }
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1).strip();
            switch (key) {
                case "mode" -> mode = switch (value) {
                    case "dry-run" -> Mode.DRY_RUN;
                    case "http" -> Mode.HTTP;
                    default -> throw new UsageException("--mode must be dry-run or http.");
                };
                case "data" -> data = Path.of(value);
                case "url" -> url = parseUrl(value);
                case "devices" -> devices = parseDevices(value);
                case "sessions" -> sessions = parseSessions(value);
                case "interval" -> intervalSeconds = parseLong("interval", value);
                case "cycles" -> cycles = parseLong("cycles", value);
                default -> throw new UsageException("Unknown option --" + key + ".");
            }
        }
        if (intervalSeconds < MIN_INTERVAL_SECONDS || intervalSeconds > MAX_INTERVAL_SECONDS) {
            throw new UsageException("--interval must be between %d and %d seconds."
                .formatted(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS));
        }
        if (cycles < 0) {
            throw new UsageException("--cycles must be 0 (forever) or positive.");
        }
        String secret = null;
        if (mode == Mode.HTTP) {
            secret = env.get(SECRET_ENV);
            if (secret == null || secret.length() < DeviceCredentials.MIN_SECRET_LENGTH) {
                throw new UsageException("http mode needs %s with at least %d characters."
                    .formatted(SECRET_ENV, DeviceCredentials.MIN_SECRET_LENGTH));
            }
        }
        return new SimulatorOptions(mode, data, url, Set.copyOf(devices), Map.copyOf(sessions),
            Duration.ofSeconds(intervalSeconds), cycles, secret);
    }

    /** In http mode every replayed device needs a storage session. */
    void checkSessionsFor(Set<String> replayedDevices) {
        if (mode != Mode.HTTP) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>(replayedDevices);
        missing.removeAll(sessions.keySet());
        if (!missing.isEmpty()) {
            throw new UsageException("http mode needs --sessions for: " + String.join(", ", missing) + ".");
        }
    }

    @Override
    public String toString() {
        return "SimulatorOptions[mode=%s, data=%s, url=%s, devices=%s, sessions=%s, interval=%s, cycles=%d]"
            .formatted(mode, data, url, devices, sessions, interval, cycles);
    }

    private static URI parseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw new UsageException("--url must be an http or https URL.");
        }
    }

    private static Set<String> parseDevices(String value) {
        Set<String> devices = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                devices.add(part.strip());
            }
        }
        if (devices.isEmpty()) {
            throw new UsageException("--devices needs at least one device id.");
        }
        return devices;
    }

    private static Map<String, Long> parseSessions(String value) {
        Map<String, Long> sessions = new LinkedHashMap<>();
        for (String part : value.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new UsageException("--sessions entries look like DEVICE=ID, got: " + part.strip());
            }
            String device = part.substring(0, eq).strip();
            long id = parseLong("sessions", part.substring(eq + 1));
            if (id < 1) {
                throw new UsageException("Session ids must be positive: " + part.strip());
            }
            if (sessions.put(device, id) != null) {
                throw new UsageException("--sessions lists " + device + " twice.");
            }
        }
        return sessions;
    }

    private static long parseLong(String option, String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException ex) {
            throw new UsageException("--" + option + " expects a whole number, got: " + value.strip());
        }
    }
}
