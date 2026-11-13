# Smart Cold Storage Monitor

A Java 21 / Spring Boot backend that collects temperature and humidity readings from cold-storage zones, detects excursions and sensor faults, and keeps an audited, role-protected record of who did what. It is built for cold-storage operators, who need to see current conditions and excursions; for administrators, who manage user access and review audit records; and for instructors, who can reproduce the demonstration from this repository. It replaces manual spot checks, which can miss short or overnight temperature changes and leave an incomplete history.

The project is the CS5010 team project of Team Intensive Care Unit. Risk estimates and alerts are classroom simulations, not food-safety or pharmaceutical-compliance decisions. The full requirements, API contract, and schema are in the Phase 1 design document (`doc/Smart_Cold_Storage_Monitor_Java_Design.md`, kept locally and not committed).

## Team

| Member | GitHub Username | Role |
|---|---|---|
| Xiyan Hou | [@XiyanHou-NEU](https://github.com/XiyanHou-NEU) | Backend foundation: SQLite persistence, authentication and sessions, account management, audit logging and review, API documentation |
| Qizhen Dong | [@AnoiExceptional](https://github.com/AnoiExceptional) | Sensor monitoring: inventory assessment, device telemetry ingestion, zone alerts, recorded-data replay, test fixtures and Python test clients |
| Tianyi Yin | [@Gaviid](https://github.com/Gaviid) | Model training: dataset preparation, Weka training and evaluation, risk-prediction model (planned for Phase 3) |
| Alexander Li | [@ByAlexanderL](https://github.com/ByAlexanderL) | Frontend: React and TypeScript dashboard (planned for Phase 3) |

## Repository Structure

The backend is one Spring Boot application with a layered package structure, as described in the Phase 1 design: controllers map requests, services own business rules and transactions, and repositories own all SQL.

```
Smart_Cold_Storage_Monitor/
  pom.xml, mvnw, mvnw.cmd          Maven build and Maven Wrapper (pinned Java 21 / Spring Boot 4.1.2)
  src/main/java/com/icu/smart_cold_storage_monitor/
    controller/ (+ dto/)           REST controllers and request/response DTOs: auth, users, audit review,
                                   device reading ingestion
    service/ (+ exception/)        Business rules: accounts, audit, first-run admin, paging, reading ingestion,
                                   device/session provisioning; typed exceptions
    repository/                    All SQL (Spring JDBC, parameterized): users, audit events, devices, storage
                                   sessions, readings, timestamp format
    model/                         Records and enums that mirror the tables
    security/                      Principals, user lookup, JSON login/logout/error handlers, session registry,
                                   device credential check and filter
    config/                        SQLite DataSource, security filter chains (browser, device ingestion), OpenAPI
    setup/                         Startup runners: first administrator; built-in sensor simulator
    web/                           Error body, request-ID filter, global exception handler
    monitoring/                    Zone monitoring: inventory assessment, SHT45 decoding, live telemetry
                                   ingestion, 5-second alert worker, accelerated replay
    simulator/                     Java reading simulator (plain Java: command line and the replay the
                                   backend starts on its own)
  src/main/resources/
    application.yaml               Defaults (localhost only, database path, ingestion, simulator, monitoring)
    application-monitoring-*.yaml  Profiles: demo (5 zones), seven-zone replay, live (7 zones + ingestion)
    schema.sql                     Idempotent schema applied on every start (users, audit_events,
                                   devices, storage_sessions, threshold_settings, readings)
  src/test/java/...                JUnit 5 / Spring Boot Test / MockMvc tests, mirroring the main packages
  testData/
    SevenZone_Demo/                Seven-zone inventory SQLite, 2,527 recorded sensor samples (JSONL),
                                   Python HTTP test clients
    ColdStorage_Demo/, SHT45_30min/  Five-zone inventory and the original 30-minute sensor recording
  docs/                            Monitoring architecture, live telemetry contract, zoning design
  doc/                             Phase 1 design and course material (git-ignored, local only)
  data/                            Runtime SQLite databases (git-ignored, created on first run) and the
                                   simulator's scenario file monitoring_timestamped.jsonl (committed)
```

## Setup and Run

**Prerequisites:** JDK 21 or newer (on machines where `java` on the PATH is older, set `JAVA_HOME` to a JDK 21 install); Python 3 with only the standard library (for the HTTP test clients). No Node.js, cloud account, or API key is required. Maven is provided by the wrapper.

```powershell
# Clone
git clone https://github.com/xiyanhou04-cloud/Smart_Cold_Storage_Monitor.git
cd Smart_Cold_Storage_Monitor

# Run tests (dependencies are downloaded on first run)
.\mvnw.cmd test

# Run the application with live telemetry ingestion and zone alerts (http://127.0.0.1:8080)
$env:APP_TELEMETRY_DEVICE_SECRET = 'local-seven-zone-demo-key'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=monitoring-live"
```

In bash use `./mvnw` and `export APP_TELEMETRY_DEVICE_SECRET=local-seven-zone-demo-key`. Without a profile (`.\mvnw.cmd spring-boot:run`), the account, authentication, and audit features run, plus design-conformant reading ingestion (`POST /api/readings`) fed by the built-in sensor simulator (see below). An executable JAR is built with `.\mvnw.cmd package`.

**First sign-in.** On first run, if no administrator exists, one is created with username `admin` and the default password `12345678`; the console prints a reminder to change it (`POST /api/users/{id}/password-reset`). Override with `APP_BOOTSTRAP_ADMIN_USERNAME` and `APP_BOOTSTRAP_ADMIN_PASSWORD`. The default is public, so change it for anything beyond a local demo.

**Configuration (environment variables):**

| Variable | Purpose | Default |
|---|---|---|
| `APP_TELEMETRY_DEVICE_SECRET` | Secret used to derive device ingestion tokens (`monitoring-live` profile) | none, required for ingestion |
| `APP_DEVICE_SECRET` | Device secret for `POST /api/readings` tokens, shared by the backend and an external simulator (`--mode=http`); at least 16 characters | backend: random per run (only the built-in simulator can post); simulator http mode: required |
| `APP_SIMULATOR_ENABLED` | Run the built-in sensor simulator loop | `true` |
| `APP_READINGS_CLEAR_ON_STARTUP` | Empty the `readings` table on every start (devices, sessions, and audit events are kept) | `true` |
| `APP_DATABASE_PATH` | Accounts, audit, devices, storage sessions, and readings SQLite file | `./data/smart-cold-storage.db` |
| `SERVER_PORT` | HTTP port | `8080` |
| `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` | First-run administrator | `admin` / `12345678` |
| `APP_API_DOCS_ENABLED` | Serve `/v3/api-docs` and Swagger UI | `true` |

Real secrets are never committed. The server binds to `127.0.0.1` only. To reset local data, stop the app and delete the files under `data/`. Normal startup keeps all data except sensor readings, which are cleared on every start by default (see below).

### Run the core use case end to end

With the backend running as above, open a second terminal at the repository root.

```powershell
$env:APP_TELEMETRY_DEVICE_SECRET = 'local-seven-zone-demo-key'   # same value as the backend
python -B .\testData\SevenZone_Demo\test_live_telemetry.py
```

When prompted for the backend password, enter the administrator password (`12345678` on a fresh database), or set `TELEMETRY_TEST_PASSWORD` to skip the prompt. The run takes about 35 seconds and exercises the full flow: seven simulated devices post authenticated readings, the backend stores them, the 5-second worker evaluates each zone, and the script reads the alerts back over the signed-in API. It asserts 56 persisted readings, all eight alert types (each opened and closed exactly once), device-credential rejection, duplicate handling, and conflicting-retry rejection.

For an accelerated replay of the recorded 30-minute feed (2,527 samples), run `python -B .\testData\SevenZone_Demo\test_recorded_live_replay.py` and enter a speed such as `100` (about 18 seconds).

### Sensor reading ingestion and the built-in simulator

As soon as the backend is running (no profile needed, nobody has to sign in), it collects sensor readings into the `readings` table of `data/smart-cold-storage.db`:

1. **Provisioning.** On startup, every device in `data/monitoring_timestamped.jsonl` (seven) is registered in `devices`, and each gets an active storage session (product type = its zone, e.g. `RAW-MEAT`) unless it already has one. A new session writes a `STORAGE_SESSION_STARTED` audit event with no actor. On later startups the existing sessions are reused.
2. **Replay loop.** A background thread replays the recorded samples (see the Java simulator below), one reading per device every 5 seconds, wrapping to the first sample after the last, forever. Each reading is stamped with its actual send time and posted over HTTP to the server's own `POST /api/readings` with that device's credential, the same path an external device uses. After a restart the scenario starts from its first sample again, into an empty `readings` table (next point).
3. **Ingestion.** `POST /api/readings` accepts only `Authorization: Device <deviceId>:<token>` (token = hex HMAC-SHA256 of the device id under `APP_DEVICE_SECRET`) for a registered, enabled device. It is stateless and needs no CSRF token; a browser session cookie is ignored (401). Valid readings are stored in one short transaction:

| Situation | Response |
|---|---|
| New reading | 201 with the stored reading |
| Same device and timestamp, same values (a retry) | 200 with the stored reading, nothing added |
| Same device and timestamp, different values | 409 `READING_CONFLICT` |
| Timestamp not after the device's latest reading | 409 `OUT_OF_ORDER` |
| Session ended | 409 `SESSION_NOT_ACTIVE` |
| Session unknown or of another device | 404 |
| Body `deviceId` differs from the credential | 403 (audited as `ACCESS_DENIED`) |
| Missing field, humidity outside 0 to 100, temperature outside -40 to 125 °C, NaN, timestamp more than 60 s ahead or before the session start | 400 |
| Missing or invalid credential, unknown or disabled device | 401 `INVALID_DEVICE_CREDENTIAL` |

Rejected readings change nothing. Timestamps are stored to the millisecond. `threshold_id` stays empty until versioned thresholds (FR-04) exist. The startup log shows `Sensor simulator started: 7 device(s) ...`; to see every accepted reading, set `logging.level.com.icu.smart_cold_storage_monitor.simulator=DEBUG` (rejected ones are logged at WARN). The table grows by about 120,000 rows per day of uptime. **To keep the database small, every start empties `readings`** before the HTTP server opens (log line `Cleared N stored reading(s) on startup`); devices, storage sessions, and audit events are kept. Set `APP_READINGS_CLEAR_ON_STARTUP=false` to keep readings across restarts, for example to demonstrate the design's restart-persistence requirement or to collect history for model training. Set `APP_SIMULATOR_ENABLED=false` to stop the loop; devices and sessions are still provisioned so an external simulator can post instead.

### Run the Java reading simulator from the command line

The simulator replays the seven-zone recording in `data/monitoring_timestamped.jsonl` (a copy of `testData/SevenZone_Demo/monitoring_timestamped.jsonl`, 361 samples per device). Each tick sends one reading per device; after a device's last sample it wraps to the first. Every reading carries the **actual send time** (UTC, milliseconds), not the recorded `measured_at`, so readings from consecutive loops always move forward in time. It is a scenario replay, not live sensing.

```powershell
# Dry run (default): print the JSON that would be posted, one line per device per tick
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.main-class=com.icu.smart_cold_storage_monitor.simulator.SimulatorMain" "-Dspring-boot.run.arguments=--devices=ESP32-SIM-001 --interval=5"

# Post to the running backend. Start the backend with the same APP_DEVICE_SECRET and
# APP_SIMULATOR_ENABLED=false (two loops on the same device would reject each other as OUT_OF_ORDER).
# The backend logs the session id of each device at startup.
$env:APP_DEVICE_SECRET = 'local-simulator-device-key'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.main-class=com.icu.smart_cold_storage_monitor.simulator.SimulatorMain" "-Dspring-boot.run.arguments=--mode=http --sessions=ESP32-SIM-001=1 --devices=ESP32-SIM-001"
```

| Option | Meaning | Default |
|---|---|---|
| `--mode=dry-run\|http` | Print the JSON, or POST it | `dry-run` |
| `--data=PATH` | Scenario JSONL | `data/monitoring_timestamped.jsonl` |
| `--url=URL` | Reading endpoint | `http://127.0.0.1:8080/api/readings` |
| `--devices=ID,...` | Devices to replay | every device in the file (7) |
| `--sessions=ID=N,...` | Storage session id per device; required for each device in http mode | none |
| `--interval=SECONDS` | Seconds between ticks, 1 to 30 | `5` |
| `--cycles=N` | Passes through the scenario, `0` = forever | `0` |

The request body follows the design's reading contract, `{deviceId, sessionId, timestamp, temperatureC, humidityPct}`, with header `Authorization: Device <deviceId>:<token>`, where the token is the hex HMAC-SHA256 of the device id under `APP_DEVICE_SECRET` (at least 16 characters; read only from the environment). A retry after a network error, timeout, or 5xx resends the identical body, timestamp included (up to 3 attempts, then the sample is dropped). 400/409 are logged and the loop continues; 404 stops that device; 401/403 stop the run. Late ticks are skipped rather than sent in a burst. Ctrl+C prints a per-device summary. On startup the file is checked: each line must match its SHT45 frame, and each device's samples must be gap-free at the recorded 5-second step.

To try the account and audit features, open Swagger UI at <http://127.0.0.1:8080/swagger-ui.html>, or use the endpoints below. Sign in with `GET /api/auth/csrf`, then `POST /api/auth/login` (form fields `username`, `password`, header `X-CSRF-TOKEN`).

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /api/auth/csrf`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` | public / signed in | Session login with CSRF protection |
| `GET/POST /api/users`, `POST /api/users/{id}/password-reset`, `POST /api/users/{id}/deactivation` | ADMIN | Account management |
| `GET /api/audit-events` | ADMIN | Audit review, filter by time, actor, event type, result; paged |
| `POST /api/readings` | device credential | Ingest one reading (`Authorization: Device <deviceId>:<token>`); no session or CSRF |
| `POST /api/telemetry/readings` | device token | Ingest one reading (`X-Device-Id`, `X-Device-Token`); `monitoring-live` only |
| `GET /api/telemetry/samples`, `GET /api/monitoring/alerts`, `GET /api/monitoring/alert-events` | signed in | Stored samples, current/closed zone alerts, ordered alert events |
| `GET /api/monitoring/assessments`, `GET /api/monitoring/replay/assessments` | signed in | Per-lot condition assessment (live and as-of replay) |
| `GET /api/monitoring/recorded-alert-replay?speed=` | signed in | Streamed accelerated replay of the recorded feed |

More detail: [live telemetry and alert interfaces](docs/LIVE_TELEMETRY_AND_ALERTS.md), [monitoring architecture](docs/MONITORING_ARCHITECTURE.md), [storage zone integration](docs/STORAGE_ZONE_INTEGRATION.md).

## Progress So Far

| Functional Requirement (from Phase 1) | Status | Notes |
|---|---|---|
| FR-01 User login and logout | Complete | Session login/logout, BCrypt, CSRF, roles (OPERATOR, ADMIN), session expiry on password reset or deactivation, first-run admin. Admins create, reset, and deactivate accounts. |
| FR-02 Sensor data ingestion | Mostly complete | Design-conformant `POST /api/readings`: device credential only (no cookie, CSRF-exempt), deviceId must match the credential, active session required, humidity 0 to 100, finite values, timestamp at most 60 s ahead, 201 new / 200 identical retry / 409 conflict or out of order, stored in `readings` with `UNIQUE(device_id, measured_at)`. On startup the backend provisions the seven devices with active storage sessions and its built-in Java simulator posts the recorded samples to this endpoint every 5 seconds with send-time timestamps, with nobody signed in. Not yet: admin storage-session API (`POST /api/storage-sessions`), threshold version and alert transition in the ingestion transaction (FR-04). The prototype's `POST /api/telemetry/readings` (`monitoring-live` profile) is unchanged. |
| FR-03 Dashboard | Not started | No frontend yet. Polling-ready JSON endpoints exist (assessments, alerts, alert events). |
| FR-04 Threshold alerts | Partial | Eight zone alert types (temperature/humidity low and high, sensor missing and invalid). One incident per type, evaluated every 5 seconds, closed on recovery. Limits come from the inventory database; configurable, versioned thresholds (`/api/settings/thresholds`) are not built. |
| FR-05 Risk prediction | Not started | No Weka model, `RiskPredictor`, or rules fallback. The lot-level condition assessment (OK, WARNING, ACTION_REQUIRED, UNKNOWN) is a rule check, not a risk prediction. |
| FR-06 Alert acknowledgment | Not started | Alerts open and close automatically; no acknowledgment endpoint or note. |
| FR-07 Audit logging | Partial | Login success/failure, logout, access denied, user created, password reset, user deactivated, storage session started (by the system at startup), written with actor, time, result, target. Threshold-change, acknowledgment, and admin storage-session events wait on those features. |
| FR-08 History and audit review | Partial | Admin audit review with filters and paging is complete. Alert history (`state=CLOSED/ALL`, alert events) and stored samples are available; the design's paged `/api/readings` and `/api/alerts` are not built. |

**Working end to end:** Start the backend with no profile and readings from seven simulated devices land in `data/smart-cold-storage.db` every 5 seconds through `POST /api/readings`. Or start the backend with the `monitoring-live` profile. Seven simulated devices post readings with per-device tokens. The backend validates and stores them in a separate telemetry SQLite file (`data/live-telemetry.sqlite`), and the scheduled worker opens and closes zone alerts. A signed-in user reads the alerts and events back over the API. Independently, an administrator can sign in, create operator accounts, reset or deactivate them, and review the resulting audit trail. Both flows run from the commands above.

## Testing So Far

- JUnit 5, Spring Boot Test, and MockMvc. Integration tests use temporary SQLite files (never another engine). Coverage: database configuration (foreign keys, WAL, transactions), login/logout/CSRF/session behavior, role enforcement, account management, audit writing and review filters, paging, first-run admin creation, OpenAPI document, the zone assessment engine, SHT45 decoding and replay, the monitoring API, the sensor data table constraints (unique device/time, one active session per device, session and threshold ownership, value and timestamp checks), the Java simulator (scenario loading and checks, looping and send-time stamps, retries with the identical payload, HTTP sending against a stub server, options), and reading ingestion (every status in the ingestion table, device credentials, cookie rejection, no CSRF or session, session provisioning and its audit event, the built-in simulator storing readings on a real server port, and the command-line simulator posting to a real server, and clearing readings on startup).
- 211 tests across 27 test classes; all passed on 2026-11-12 (`.\mvnw.cmd test`, 0 failures, 0 errors, 0 skipped).
- The Python clients under `testData/SevenZone_Demo/` are HTTP-level checks against a running backend and are not part of `mvnw test`.
- Not yet covered: the unbuilt features (admin storage-session API, thresholds, acknowledgment, prediction) and the frontend.

## Next Steps (Phase 3)

- Add the admin storage-session API and versioned thresholds (`POST /api/storage-sessions`, `GET/PUT /api/settings/thresholds`), and record the threshold version and alert transition in the reading transaction. Decide with the team whether the prototype's `POST /api/telemetry/readings` is retired.
- Add alert acknowledgment with notes, with its audit event in the same transaction; extend the audit catalog to threshold and storage-session changes.
- Implement risk prediction: shared feature calculation, a Weka training entry point and evaluation report, the `RiskPredictor` interface, and the rules fallback.
- Build the React and TypeScript dashboard (2-second polling, trend chart, alerts, stale-data indication) served by Spring Boot; add paged readings and alert history APIs.
- Add the paged `GET /api/readings` and `GET /api/readings/latest`, and measure receipt-to-display latency against the 5-second target.

## Team Contributions

| Member | Contributions This Phase |
|---|---|
| Xiyan Hou | Spring Boot project setup; SQLite persistence (`DatabaseConfig`, idempotent `schema.sql`, `SqliteExceptionTranslator`); FR-01 authentication (`SecurityConfig`, JSON login/logout handlers, session registry and expiry, CSRF); first-run administrator (`InitialAdminService`, `AdminBootstrapRunner`); account management (`UserAccountService`, `/api/users`); audit logging and admin review (`AuditService`, `AuditReviewService`, `/api/audit-events`); error handling and request IDs; Swagger/OpenAPI docs; tests for these components (`AuthApiTest`, `UserAdminApiTest`, `AuditReviewApiTest`, `AuditServiceTest`, `UserAccountServiceTest`, `PageQueryTest`, `DatabaseConfigTest`, `AdminBootstrapRunnerTest`, `OpenApiDocsTest`); this README. |
| Qizhen Dong | `monitoring` package: inventory and sensor adapters, `AssessmentEngine` and assessment API, SHT45 frame codec with CRC checks, live telemetry ingestion with device tokens (`LiveTelemetryService`, `LiveTelemetrySecurity`), 5-second zone alert worker, recorded-alert replay endpoint; Spring profiles; five- and seven-zone fixtures and recorded sensor data; Python HTTP test clients; design docs under `docs/`; tests `AssessmentEngineTest`, `MonitoringApiTest`, `MonitoringIntegrationTest`, `SevenZoneReplayTest`. |
| Tianyi Yin | Model training: dataset preparation, Weka training and evaluation, risk-prediction model (planned for Phase 3) |
| Alexander Li | Frontend: React and TypeScript dashboard (planned for Phase 3) |
