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
    controller/ (+ dto/)           REST controllers and request/response DTOs: auth, users, audit review
    service/ (+ exception/)        Business rules: accounts, audit, first-run admin, paging; typed exceptions
    repository/                    All SQL (Spring JDBC, parameterized): users, audit events, timestamp format
    model/                         Records and enums that mirror the tables
    security/                      Principal, user lookup, JSON login/logout/error handlers, session registry
    config/                        SQLite DataSource, security filter chain, OpenAPI docs
    setup/                         Startup runner that creates the first administrator
    web/                           Error body, request-ID filter, global exception handler
    monitoring/                    Zone monitoring: inventory assessment, SHT45 decoding, live telemetry
                                   ingestion, 5-second alert worker, accelerated replay
  src/main/resources/
    application.yaml               Defaults (localhost only, database path, monitoring paths)
    application-monitoring-*.yaml  Profiles: demo (5 zones), seven-zone replay, live (7 zones + ingestion)
    schema.sql                     Idempotent schema applied on every start (users, audit_events)
  src/test/java/...                JUnit 5 / Spring Boot Test / MockMvc tests, mirroring the main packages
  testData/
    SevenZone_Demo/                Seven-zone inventory SQLite, 2,527 recorded sensor samples (JSONL),
                                   Python HTTP test clients
    ColdStorage_Demo/, SHT45_30min/  Five-zone inventory and the original 30-minute sensor recording
  docs/                            Monitoring architecture, live telemetry contract, zoning design
  doc/                             Phase 1 design and course material (git-ignored, local only)
  data/                            Runtime SQLite databases (git-ignored, created on first run)
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

In bash use `./mvnw` and `export APP_TELEMETRY_DEVICE_SECRET=local-seven-zone-demo-key`. Without a profile (`.\mvnw.cmd spring-boot:run`), only the account, authentication, and audit features run. An executable JAR is built with `.\mvnw.cmd package`.

**First sign-in.** On first run, if no administrator exists, one is created with username `admin` and the default password `12345678`; the console prints a reminder to change it (`POST /api/users/{id}/password-reset`). Override with `APP_BOOTSTRAP_ADMIN_USERNAME` and `APP_BOOTSTRAP_ADMIN_PASSWORD`. The default is public, so change it for anything beyond a local demo.

**Configuration (environment variables):**

| Variable | Purpose | Default |
|---|---|---|
| `APP_TELEMETRY_DEVICE_SECRET` | Secret used to derive device ingestion tokens (`monitoring-live` profile) | none, required for ingestion |
| `APP_DATABASE_PATH` | Account and audit SQLite file | `./data/smart-cold-storage.db` |
| `SERVER_PORT` | HTTP port | `8080` |
| `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` | First-run administrator | `admin` / `12345678` |
| `APP_API_DOCS_ENABLED` | Serve `/v3/api-docs` and Swagger UI | `true` |

Real secrets are never committed. The server binds to `127.0.0.1` only. To reset local data, stop the app and delete the files under `data/` (the app never resets them on normal startup).

### Run the core use case end to end

With the backend running as above, open a second terminal at the repository root.

```powershell
$env:APP_TELEMETRY_DEVICE_SECRET = 'local-seven-zone-demo-key'   # same value as the backend
python -B .\testData\SevenZone_Demo\test_live_telemetry.py
```

When prompted for the backend password, enter the administrator password (`12345678` on a fresh database), or set `TELEMETRY_TEST_PASSWORD` to skip the prompt. The run takes about 35 seconds and exercises the full flow: seven simulated devices post authenticated readings, the backend stores them, the 5-second worker evaluates each zone, and the script reads the alerts back over the signed-in API. It asserts 56 persisted readings, all eight alert types (each opened and closed exactly once), device-credential rejection, duplicate handling, and conflicting-retry rejection.

For an accelerated replay of the recorded 30-minute feed (2,527 samples), run `python -B .\testData\SevenZone_Demo\test_recorded_live_replay.py` and enter a speed such as `100` (about 18 seconds).

To try the account and audit features, open Swagger UI at <http://127.0.0.1:8080/swagger-ui.html>, or use the endpoints below. Sign in with `GET /api/auth/csrf`, then `POST /api/auth/login` (form fields `username`, `password`, header `X-CSRF-TOKEN`).

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /api/auth/csrf`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` | public / signed in | Session login with CSRF protection |
| `GET/POST /api/users`, `POST /api/users/{id}/password-reset`, `POST /api/users/{id}/deactivation` | ADMIN | Account management |
| `GET /api/audit-events` | ADMIN | Audit review, filter by time, actor, event type, result; paged |
| `POST /api/telemetry/readings` | device token | Ingest one reading (`X-Device-Id`, `X-Device-Token`); `monitoring-live` only |
| `GET /api/telemetry/samples`, `GET /api/monitoring/alerts`, `GET /api/monitoring/alert-events` | signed in | Stored samples, current/closed zone alerts, ordered alert events |
| `GET /api/monitoring/assessments`, `GET /api/monitoring/replay/assessments` | signed in | Per-lot condition assessment (live and as-of replay) |
| `GET /api/monitoring/recorded-alert-replay?speed=` | signed in | Streamed accelerated replay of the recorded feed |

More detail: [live telemetry and alert interfaces](docs/LIVE_TELEMETRY_AND_ALERTS.md), [monitoring architecture](docs/MONITORING_ARCHITECTURE.md), [storage zone integration](docs/STORAGE_ZONE_INTEGRATION.md).

## Progress So Far

| Functional Requirement (from Phase 1) | Status | Notes |
|---|---|---|
| FR-01 User login and logout | Complete | Session login/logout, BCrypt, CSRF, roles (OPERATOR, ADMIN), session expiry on password reset or deactivation, first-run admin. Admins create, reset, and deactivate accounts. |
| FR-02 Sensor data ingestion | Partial | Device-authenticated `POST /api/telemetry/readings` with per-channel quality states and idempotent retries (200 identical, 409 conflicting, 201 new). It uses a zone/boot/sequence contract in the `monitoring-live` profile, not the design's `POST /api/readings` with storage sessions, which is not built. |
| FR-03 Dashboard | Not started | No frontend yet. Polling-ready JSON endpoints exist (assessments, alerts, alert events). |
| FR-04 Threshold alerts | Partial | Eight zone alert types (temperature/humidity low and high, sensor missing and invalid). One incident per type, evaluated every 5 seconds, closed on recovery. Limits come from the inventory database; configurable, versioned thresholds (`/api/settings/thresholds`) are not built. |
| FR-05 Risk prediction | Not started | No Weka model, `RiskPredictor`, or rules fallback. The lot-level condition assessment (OK, WARNING, ACTION_REQUIRED, UNKNOWN) is a rule check, not a risk prediction. |
| FR-06 Alert acknowledgment | Not started | Alerts open and close automatically; no acknowledgment endpoint or note. |
| FR-07 Audit logging | Partial | Login success/failure, logout, access denied, user created, password reset, user deactivated, written with actor, time, result, target. Threshold-change, acknowledgment, and storage-session events wait on those features. |
| FR-08 History and audit review | Partial | Admin audit review with filters and paging is complete. Alert history (`state=CLOSED/ALL`, alert events) and stored samples are available; the design's paged `/api/readings` and `/api/alerts` are not built. |

**Working end to end:** Start the backend with the `monitoring-live` profile. Seven simulated devices post readings with per-device tokens. The backend validates and stores them in a separate telemetry SQLite file (`data/live-telemetry.sqlite`), and the scheduled worker opens and closes zone alerts. A signed-in user reads the alerts and events back over the API. Independently, an administrator can sign in, create operator accounts, reset or deactivate them, and review the resulting audit trail. Both flows run from the commands above.

## Testing So Far

- JUnit 5, Spring Boot Test, and MockMvc. Integration tests use temporary SQLite files (never another engine). Coverage: database configuration (foreign keys, WAL, transactions), login/logout/CSRF/session behavior, role enforcement, account management, audit writing and review filters, paging, first-run admin creation, OpenAPI document, the zone assessment engine, SHT45 decoding and replay, and the monitoring API.
- 112 tests across 14 test classes; all passed on 2026-11-07 (`.\mvnw.cmd test`, 0 failures, 0 errors, 0 skipped).
- The Python clients under `testData/SevenZone_Demo/` are HTTP-level checks against a running backend and are not part of `mvnw test`.
- Not yet covered: the unbuilt features (storage sessions, thresholds, acknowledgment, prediction) and the frontend.

## Next Steps (Phase 3)

- Add devices, storage sessions, and versioned thresholds (`POST /api/storage-sessions`, `GET/PUT /api/settings/thresholds`), and align ingestion with the design's `POST /api/readings` contract or document the zone-based contract as the replacement.
- Add alert acknowledgment with notes, with its audit event in the same transaction; extend the audit catalog to threshold and storage-session changes.
- Implement risk prediction: shared feature calculation, a Weka training entry point and evaluation report, the `RiskPredictor` interface, and the rules fallback.
- Build the React and TypeScript dashboard (2-second polling, trend chart, alerts, stale-data indication) served by Spring Boot; add paged readings and alert history APIs.
- Add the Java simulator with scenario presets, and measure receipt-to-display latency against the 5-second target.

## Team Contributions

| Member | Contributions This Phase |
|---|---|
| Xiyan Hou | Spring Boot project setup; SQLite persistence (`DatabaseConfig`, idempotent `schema.sql`, `SqliteExceptionTranslator`); FR-01 authentication (`SecurityConfig`, JSON login/logout handlers, session registry and expiry, CSRF); first-run administrator (`InitialAdminService`, `AdminBootstrapRunner`); account management (`UserAccountService`, `/api/users`); audit logging and admin review (`AuditService`, `AuditReviewService`, `/api/audit-events`); error handling and request IDs; Swagger/OpenAPI docs; tests for these components (`AuthApiTest`, `UserAdminApiTest`, `AuditReviewApiTest`, `AuditServiceTest`, `UserAccountServiceTest`, `PageQueryTest`, `DatabaseConfigTest`, `AdminBootstrapRunnerTest`, `OpenApiDocsTest`); this README. |
| Qizhen Dong | `monitoring` package: inventory and sensor adapters, `AssessmentEngine` and assessment API, SHT45 frame codec with CRC checks, live telemetry ingestion with device tokens (`LiveTelemetryService`, `LiveTelemetrySecurity`), 5-second zone alert worker, recorded-alert replay endpoint; Spring profiles; five- and seven-zone fixtures and recorded sensor data; Python HTTP test clients; design docs under `docs/`; tests `AssessmentEngineTest`, `MonitoringApiTest`, `MonitoringIntegrationTest`, `SevenZoneReplayTest`. |
| Tianyi Yin | Model training: dataset preparation, Weka training and evaluation, risk-prediction model (planned for Phase 3) |
| Alexander Li | Frontend: React and TypeScript dashboard (planned for Phase 3) |
