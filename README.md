# Smart Cold Storage Monitor

Smart Cold Storage Monitor collects temperature and humidity readings from seven simulated cold-storage zones, shows current conditions and trends on a web dashboard, opens alerts when a zone leaves its limits, and estimates Low, Medium, or High storage risk with a Weka Random Forest (with a rule-based fallback). Every account change, threshold change, and alert acknowledgment is recorded in an audit trail that administrators can filter. The system is a Java 21 / Spring Boot backend with SQLite storage, a Java sensor simulator, and a React + TypeScript dashboard. It is built for cold-storage operators, who watch conditions and respond to alerts; for administrators, who manage accounts, limits, and audit review; and for instructors, who can reproduce the whole demonstration on a laptop from this repository. It is the CS5010 team project of Team Intensive Care Unit. Risk estimates and alerts are classroom simulations, not food-safety or pharmaceutical-compliance decisions.

## Team

| Member | GitHub Username | Role |
|---|---|---|
| Xiyan Hou | [@XiyanHou-NEU](https://github.com/XiyanHou-NEU) | Backend: persistence, authentication, accounts, audit, reading ingestion, thresholds, alerts, risk alerts, history APIs, API docs |
| Qizhen Dong | [@AnoiExceptional](https://github.com/AnoiExceptional) | Sensor data: Java sensor simulator, zone-monitoring prototype (zone assessment, telemetry ingestion, zone alerts, recorded-data replay), sensor recordings and fixtures, Python test clients |
| Tianyi Yin | [@Gaviid](https://github.com/Gaviid) | Machine learning: scenario generation, Weka training and evaluation, risk prediction in the backend |
| Alexander Li | [@ByAlexanderL](https://github.com/ByAlexanderL) | Frontend: React and TypeScript dashboard |

## Repository Structure

The backend is one Spring Boot application with a layered package structure: controllers map requests, services own business rules and transactions, and repositories own all SQL.

```
Smart_Cold_Storage_Monitor/
  pom.xml, mvnw, mvnw.cmd          Maven build and Maven Wrapper (Java 21, Spring Boot 4.1.2, Weka 3.8.6)
  src/main/java/com/icu/smart_cold_storage_monitor/
    controller/ (+ dto/)           REST controllers and request/response DTOs: auth, users, audit review,
                                   readings (ingestion, query, latest), thresholds, alerts, predictions
    service/ (+ exception/)        Business rules and transactions: accounts, audit, first-run admin, paging,
                                   ingestion, device/session provisioning, thresholds, threshold and risk alerts,
                                   acknowledgment, history queries; typed exceptions
    repository/                    All SQL (Spring JDBC, parameterized): users, audit events, devices, sessions,
                                   thresholds, readings, predictions, alerts; timestamp format
    model/                         Records and enums that mirror the tables
    security/                      Principals, user lookup, JSON login/logout/error handlers, session registry,
                                   device credential check and filter
    config/                        SQLite DataSource, security filter chains (browser, device), OpenAPI,
                                   threshold defaults
    setup/                         Startup runners: first administrator, schema upgrade, threshold backfill,
                                   optional reading reset, built-in sensor simulator
    web/                           Error body, request-ID filter, global exception handler
    ml/                            Risk features, Weka and rules predictors, prediction worker, monitoring-run
                                   markers, offline scenario generation and training entry points
    simulator/                     Java reading simulator (plain Java: command line, and the replay the
                                   backend starts on its own)
    monitoring/                    Zone-monitoring prototype (separate path, Spring profiles only)
  src/main/resources/
    application.yaml               Defaults (localhost only, database, simulator, thresholds, risk model)
    application-monitoring-*.yaml  Prototype profiles: demo (5 zones), seven-zone replay, live ingestion
    schema.sql                     Idempotent schema applied on every start
    risk-model/                    Shipped Random Forest model, Weka header, and metadata
    static/                        Target of the frontend's build:backend (served by Spring Boot)
  src/test/java/...                JUnit 5 / Spring Boot Test / MockMvc tests, mirroring the main packages
  frontend/                        React + TypeScript dashboard (Vite, Vitest); see frontend/README.md
  testData/
    SevenZone_Demo/                Seven-zone inventory SQLite, 2,527 recorded sensor samples, Python HTTP clients
    ColdStorage_Demo/, SHT45_30min/  Five-zone inventory and the original 30-minute sensor recording
    ML_Scenarios/                  Configuration for the synthetic training scenarios
  docs/                            ML training report, monitoring prototype architecture and telemetry interface,
                                   storage-zone notes
  data/                            Runtime SQLite databases (git-ignored, created on first run) and the
                                   simulator's scenario file monitoring_timestamped.jsonl (committed)
```

## Setup and Run

**Prerequisites**

- JDK 21 or newer. Maven is provided by the wrapper. If `java` on the PATH is older, set `JAVA_HOME` to a JDK 21 install first.
- Node.js 20 or newer, for the dashboard.
- Optional: Python 3 (standard library only) for the HTTP test clients of the monitoring prototype.

No cloud account, API key, or physical sensor is needed.

**Install and test**

```powershell
git clone https://github.com/xiyanhou04-cloud/Smart_Cold_Storage_Monitor.git
cd Smart_Cold_Storage_Monitor
.\mvnw.cmd test                  # backend tests; dependencies download on the first run
cd frontend
npm install
npm test                         # frontend tests
cd ..
```

In bash, use `./mvnw` instead of `.\mvnw.cmd`. If `./mvnw` reports "permission denied" (the execute bit is lost in a zip download), run `chmod +x mvnw`.

**Run the backend**

```powershell
.\mvnw.cmd spring-boot:run       # http://127.0.0.1:8080
```

On startup the backend creates `data/smart-cold-storage.db`, registers seven simulated devices (one per storage zone), starts a storage session for each with its zone's default limits, and its built-in simulator posts one reading per device every 5 seconds to its own `POST /api/readings`. Each reading is checked against the limits, and the risk model scores it shortly after. The log shows `Loaded storage risk model random-forest-ml2-v1` and `Sensor simulator started: 7 device(s) ...`. An executable JAR is built with `.\mvnw.cmd package`.

**Run the dashboard**

Option A, development server (backend running as above, in a second terminal):

```powershell
cd frontend
npm run dev                      # http://localhost:5173, proxies /api to http://127.0.0.1:8080
```

Option B, served by Spring Boot from the same origin (the demonstration setup):

```powershell
cd frontend
npm run build:backend            # writes the build into src/main/resources/static
cd ..
.\mvnw.cmd spring-boot:run       # dashboard at http://127.0.0.1:8080/
```

`npm run dev:mock` runs the dashboard against an in-memory mock API with no backend.

**First sign-in.** If no administrator exists, the backend creates one with username `admin` and password `12345678` and prints a reminder to change it. The default is public, so change it (Users page, or `POST /api/users/{id}/password-reset`) for anything beyond a local demo, or set `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` before the first start. Administrators create operator accounts on the Users page.

**What to try.** The dashboard shows one card per zone with its latest values against its limits, a trend chart (5 minutes to 6 hours), and stale-data warnings. The recorded scenario contains excursions, so threshold and risk alerts open and resolve on their own; acknowledge one on the Alerts page. Sign in as an operator and request a limit change on the Thresholds page, then approve it as the administrator. The Audit page (administrators only) shows the matching events. The API is documented at <http://127.0.0.1:8080/swagger-ui.html>.

**Configuration (environment variables)**

| Variable | Purpose | Default |
|---|---|---|
| `APP_DATABASE_PATH` | SQLite file for all application data | `./data/smart-cold-storage.db` |
| `SERVER_PORT` | HTTP port (the server binds to `127.0.0.1` only) | `8080` |
| `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` | First-run administrator | `admin` / `12345678` |
| `APP_DEVICE_SECRET` | Secret for device tokens on `POST /api/readings`, at least 16 characters; must match an external simulator's | random per run (only the built-in simulator can post) |
| `APP_SIMULATOR_ENABLED` | Run the built-in simulator loop (devices and sessions are provisioned either way) | `true` |
| `APP_READINGS_CLEAR_ON_STARTUP` | Empty readings and their predictions at startup (devices, sessions, thresholds, alerts, and audit events are kept) | `false` |
| `APP_RISK_MODEL_BASE` | Model location (`classpath:` or `file:`); `classpath:/risk-model/absent` demonstrates the rules fallback | `classpath:/risk-model/random-forest` |
| `APP_RISK_STALE_AFTER` | Age after which the latest reading is reported as stale | `PT30S` |
| `APP_RISK_ALERT_WINDOW` | Window for aggregating risk predictions into one alert | `PT5M` |
| `APP_API_DOCS_ENABLED` | Serve `/v3/api-docs` and Swagger UI | `true` |
| `BACKEND_URL` (frontend dev server) | Proxy target for `/api` | `http://127.0.0.1:8080` |

Real secrets are never committed. To reset local data, stop the app and delete `data/smart-cold-storage.db` (and its `-wal` / `-shm` files). A database created by an older build is upgraded in place on startup.

### API overview

Browser clients sign in with `GET /api/auth/csrf`, then `POST /api/auth/login` (form fields `username`, `password`, header `X-CSRF-TOKEN`). Every state-changing browser request needs the CSRF token. Errors return `code`, `message`, `fieldErrors` (when relevant), and a request ID. History endpoints are paged (`page`, `size`, default 50, max 200).

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /api/auth/csrf`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` | public / signed in | Session login and logout with CSRF protection |
| `GET/POST /api/users`, `POST /api/users/{id}/password-reset`, `POST /api/users/{id}/deactivation` | ADMIN | Account management |
| `GET /api/audit-events` | ADMIN | Audit review, filtered by time, actor, event type, and result |
| `POST /api/readings` | device credential | Ingest one reading (`Authorization: Device <deviceId>:<token>`, no cookie or CSRF) |
| `GET /api/readings/query` | signed in | Readings grouped by device for charts; filter by device, metric, time range (up to 6 hours) |
| `GET /api/readings/latest` | signed in | Latest reading per device, whether it is stale, and its risk prediction |
| `GET /api/predictions` | signed in | Risk prediction history; filter by device, risk level, and measured time |
| `GET /api/settings/thresholds`, `GET/PUT /api/settings/thresholds/{deviceId}` | signed in | Current limits per device; set limits (ADMIN applies, OPERATOR requests) |
| `GET /api/settings/threshold-requests`, `POST .../{id}/cancellation` | signed in | Limit change requests (operators see their own); cancel your own |
| `POST /api/settings/threshold-requests/{id}/approval`, `.../rejection` | ADMIN | Review a pending change request |
| `GET /api/alerts`, `GET /api/alerts/{id}` | signed in | Alert history (filter by device, kind, state, acknowledgment, opening time) and one alert |
| `POST /api/alerts/{id}/acknowledgment` | signed in | Acknowledge an alert with an optional note (up to 500 characters) |

**Reading ingestion.** A reading is `{deviceId, sessionId, timestamp, temperatureC, humidityPct}`; the token is the hex HMAC-SHA256 of the device id under `APP_DEVICE_SECRET`. A new reading returns 201; an identical retry returns 200 with the stored reading and adds nothing; the same device and time with different values returns 409 `READING_CONFLICT`; a time not after the device's latest reading returns 409 `OUT_OF_ORDER`; an ended session returns 409 `SESSION_NOT_ACTIVE`; invalid values (humidity outside 0 to 100, temperature outside -40 to 125 °C, a timestamp more than 60 s ahead) return 400; a bad credential returns 401. Rejected readings change nothing.

**Thresholds.** Each device has lower and upper temperature (°C) and humidity (%RH) limits, versioned per storage session; a value equal to a limit is in range. A new session starts from its zone's default (`app.thresholds` in `application.yaml`). An administrator's change applies at once; an operator's change becomes a request that an administrator approves or rejects. A change applies only to later readings.

**Alerts.** Threshold alerts are opened, updated, and resolved in the same transaction that stores the reading: one active alert per session and metric, updated while the excursion continues, resolved by the next in-range reading. Risk alerts come from MEDIUM and HIGH predictions, aggregated per device and severity over a 5-minute window so a prediction every 5 seconds does not flood the list. Acknowledgment records the user, time, and note without resolving the alert; a repeated acknowledgment returns the first one.

### Command-line Java simulator

The simulator replays the seven-zone recording in `data/monitoring_timestamped.jsonl` and stamps each reading with its send time. It can run on its own, for example as an external device. Dry run (the default) prints the JSON it would send:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.main-class=com.icu.smart_cold_storage_monitor.simulator.SimulatorMain" "-Dspring-boot.run.arguments=--devices=ESP32-SIM-001 --interval=5"
```

To post to a backend, start the backend with the same `APP_DEVICE_SECRET` and with `APP_SIMULATOR_ENABLED=false` (two loops on one device reject each other as out of order), then add `--mode=http --sessions=ESP32-SIM-001=1` (the backend logs each device's session id at startup). `--help` lists all options.

### Risk model: training and demonstration

Training is a separate command and never runs inside the backend. To reproduce the shipped model, generate the 70 synthetic scenarios and train both Weka candidates into a new output directory:

```powershell
.\mvnw.cmd -B compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerationMain"
.\mvnw.cmd -B compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.icu.smart_cold_storage_monitor.ml.ExpandedRiskTrainingMain" "-Dexec.args=target/ml-scenarios target/ml-risk-reproduction"
```

On the held-out test scenarios, the selected Random Forest (60 trees, depth 8) reached 0.98 accuracy and 0.98 Macro-F1, with High-class recall 1.00; logistic regression reached 0.94 and 0.93. The labels come from the risk rules, so the models learn a simulated risk policy rather than measured spoilage. Settings, the split, confusion matrices, and timings are in the [ML training report](docs/ML_TRAINING_REPORT.md).

At runtime, `GET /api/readings/latest` returns each device's latest prediction: `riskLevel`, `source` (`MODEL` or `RULES`), `modelOrRuleVersion`, `predictedAt`, and `qualityFlag`. To see the rules fallback, start with a fresh `APP_DATABASE_PATH` and `APP_RISK_MODEL_BASE=classpath:/risk-model/absent`: predictions then report `RULES` and `MODEL_MISSING` while threshold alerts continue. A reading that ends a gap of more than 30 seconds (including a restart) is also scored by the rules, at least MEDIUM, with a gap quality flag. Predictions that were still pending at shutdown are processed on the next start.

When running the JAR from outside the repository, pass the simulator scenario explicitly: `java -jar Smart_Cold_Storage_Monitor.jar --app.simulator.data=C:/path/to/monitoring_timestamped.jsonl`. Without it, the backend starts and logs that the scenario could not be loaded.

### Zone-monitoring prototype

An earlier, separate monitoring path lives in the `monitoring` package and runs only with a Spring profile. It has its own telemetry endpoint (`POST /api/telemetry/readings`), its own SQLite file (`data/live-telemetry.sqlite`), a 5-second zone alert worker with eight alert types, per-lot assessments, and an accelerated replay of the recorded feed:

```powershell
$env:APP_TELEMETRY_DEVICE_SECRET = 'local-seven-zone-demo-key'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=monitoring-live"
python -B .\testData\SevenZone_Demo\test_live_telemetry.py      # in a second terminal, same secret
```

The script signs in as the administrator, posts readings from seven devices, and checks that all eight alert types open and close once (about 35 seconds). See [live telemetry and alerts](docs/LIVE_TELEMETRY_AND_ALERTS.md) and [monitoring architecture](docs/MONITORING_ARCHITECTURE.md).

## Feature Status

| Functional Requirement (from Phase 1) | Status | Where to Find It |
|---|---|---|
| FR-01 User login and logout | Complete | Backend: `config/SecurityConfig`, `security/` (JSON login/logout handlers, session registry and expiry), `service/UserAccountService`, `service/InitialAdminService`, `controller/AuthController`, `controller/UserAdminController`. Frontend: `pages/LoginPage.tsx`, `auth/AuthContext.tsx`, `pages/UsersPage.tsx` |
| FR-02 Sensor data ingestion | Complete | `controller/ReadingController`, `service/ReadingIngestionService`, `config/DeviceIngestionSecurityConfig`, `security/DeviceAuthenticationFilter`, `security/DeviceCredentialService`, `repository/ReadingRepository`; simulator in `simulator/` and `setup/SensorSimulatorRunner`. Storage sessions are started automatically at startup (see Known Issues) |
| FR-03 Dashboard | Complete | Frontend `pages/DashboardPage.tsx` (2-second polling, zone cards, trend charts with the allowed band), `domain/status.ts` (stale and no-data states); backend `controller/ReadingQueryController`, `service/ReadingQueryService` (`GET /api/readings/query`, `GET /api/readings/latest`) |
| FR-04 Threshold alerts | Complete | `service/ThresholdService`, `service/ThresholdAlertService`, `controller/ThresholdController`, `config/ThresholdProperties`, `repository/AlertRepository`; frontend `pages/ThresholdsPage.tsx`, `pages/AlertsPage.tsx` |
| FR-05 Risk prediction | Partial | Backend complete: `ml/ReadingRiskService`, `ml/WekaRiskPredictor`, `ml/RulesRiskPredictor`, `ml/RiskPredictionWorker`, `service/RiskAlertService`, `controller/PredictionController`, model in `src/main/resources/risk-model/`. The dashboard lists risk alerts but does not yet show each zone's current risk level, calculation time, and source |
| FR-06 Alert acknowledgment | Complete | `controller/AlertController`, `service/AlertAcknowledgmentService`; frontend `pages/AlertsPage.tsx` |
| FR-07 Audit logging | Complete | `service/AuditService`, `repository/AuditEventRepository`, auth handlers in `security/`; events written in the same transaction by `UserAccountService`, `ThresholdService`, `AlertAcknowledgmentService`, `DeviceSessionService` |
| FR-08 History | Complete | `GET /api/alerts` (`service/AlertQueryService`), `GET /api/readings/query`, `GET /api/predictions` (`service/PredictionQueryService`), `GET /api/audit-events` (`service/AuditReviewService`); frontend `pages/AlertsPage.tsx`, `pages/AuditPage.tsx`, dashboard trend range |

## Scope and Design Changes Since Phase 1

| Change | Reason |
|---|---|
| Seven devices (one per storage zone) instead of one monitored device; each device stores its zone in `devices.location_code` | The recorded sensor data covers seven zones with different products and limits, and a multi-zone dashboard is closer to a real cold room. |
| The backend runs the simulator itself and provisions devices and storage sessions at startup; the admin storage-session API is not built yet | The demonstration works with one command and nobody signed in, and readings use the same authenticated endpoint as an external device. |
| Humidity limits added next to temperature limits | Several zones (produce, eggs) are sensitive to humidity, and the recorded data includes it. |
| Operators may request limit changes, which an administrator approves or rejects; administrators still apply changes directly | Operators notice problems first, while control stays with administrators; every step is audited. |
| Zone default limits; a new storage session starts from its zone's default | Each zone needs different limits, and carrying limits over from a different product's session would be wrong. |
| One `alerts` table for threshold and risk alerts, with one severity scale and one acknowledgment flow | Operators see and acknowledge every alert in one list. |
| Risk alerts aggregate MEDIUM and HIGH predictions per device over 5 minutes | A prediction arrives every 5 seconds; one alert per prediction would flood the list. |
| A data gap affects only the reading that ends it (rules fallback, at least MEDIUM); later readings use the model again and keep the gap as history | Treating the whole rest of the session as incomplete would switch the model off for good after a single restart. |
| Added endpoints: `GET /api/readings/query`, `GET /api/predictions`, threshold change requests, password reset and deactivation; the paged readings list is replaced by the time-range query for now | The dashboard needs chart-ready series per device, and account management needs more than account creation. |
| First administrator is created automatically on first start with a documented default password instead of a separate setup command | Simpler classroom setup; the console reminds the administrator to change it, and environment variables override it. |
| Zone-monitoring prototype (`monitoring` package) kept as a separate path under Spring profiles | It was built in Phase 2 with its own data model; the team has not yet decided whether to merge it into the main path or retire it. |
| OpenAPI / Swagger UI added | Lets teammates and graders try the API without a client. |

## Design Patterns and Principles Applied

| Pattern / Principle | Where | Why |
|---|---|---|
| Layered architecture (MVC on the server) | `controller` → `service` → `repository` | Controllers only map and validate requests, services own rules and transactions, repositories own SQL, so each layer is tested and changed separately. |
| Repository | `repository/*Repository` (Spring `JdbcClient`, parameterized SQL) | Keeps all SQL in one place and out of business code; prevents SQL injection. |
| Strategy with fallback | `ml/RiskPredictor`, `WekaRiskPredictor`, `RulesRiskPredictor` | The risk service calls one interface; a missing or incompatible model switches to the rules without changing callers. |
| Strategy | `simulator/ReadingSender` (`ConsoleReadingSender`, `HttpReadingSender`) | The same replay loop prints JSON in dry-run mode or posts it over HTTP. |
| Facade | `simulator/ScenarioReplay`; frontend `api/http.ts` `HttpClient` | The backend uses the plain-Java simulator through one class; frontend pages never call `fetch` directly (CSRF, JSON, and errors handled in one place). |
| Observer (events) | `ReadingIngestionService` publishes `ReadingAccepted`; `RiskPredictionWorker` listens after commit; frontend `HttpClient.onUnauthorized` → `AuthProvider` | Prediction runs only after the reading is committed, so it can never roll back a reading; any 401 signs the UI out. |
| Durable job queue (transactional outbox) | `prediction_jobs` filled by a trigger in the reading transaction (`PredictionRepository.installTriggers`), drained by `RiskPredictionWorker` | Every committed reading gets a prediction, even after a crash or restart. |
| State machine | `service/ThresholdAlertService.evaluate`, `service/RiskAlertService` | Explicit open / update / resolve transitions keep one active alert per excursion. |
| Chain of Responsibility | Spring Security filter chains in `SecurityConfig` and `DeviceIngestionSecurityConfig`, `DeviceAuthenticationFilter` | Browser sessions and device credentials are checked by separate chains; the CSRF exemption covers only the ingestion endpoint. |
| Adapter / exception translation | `config/SqliteExceptionTranslator`, `web/GlobalExceptionHandler` | Maps SQLite driver errors (which carry no SQL state) to Spring exceptions, and every error to one `ApiError` body. |
| Dependency injection | Constructor injection throughout; injected `Clock` (`DatabaseConfig.clock`); frontend `api/ApiContext.tsx` | Tests replace time, HTTP, and the backend (`FakeBackend`) without global state. |
| Immutable data objects | Java records in `model` and `controller/dto` | Values cannot change after creation, and DTOs keep the API format separate from table rows. |
| Least privilege and fail-safe defaults | Roles checked in services as well as URL rules; device credentials grant ingestion only; every other `/api/**` path requires sign-in | A missed URL rule or a stolen device token cannot reach admin functions. |

## Testing

- **Unit tests:** Backend: JUnit 5, 138 tests in 23 classes, covering threshold limit validation, paging, device credentials, the simulator (scenario loading and checks, looping, retries with the same payload, HTTP sending against a stub server, options), risk features and gap handling, Weka model loading and checks, the predictors, scenario generation and training, and the zone assessment engine. Frontend: Vitest, 31 unit tests for status and freshness rules, formatting, the HTTP client (CSRF, retry, 401 handling, errors), polling, and limit validation.
- **Integration tests:** Backend: Spring Boot Test and MockMvc on temporary SQLite files, 232 tests in 26 classes. Workflows: login, logout, CSRF and session expiry; role enforcement; account management with audit events; audit review filters; reading ingestion with every status code; the built-in and command-line simulators posting to a real server port; dashboard queries; threshold changes, requests, approval, and rejection; threshold alerts opened, updated, and resolved in the reading transaction; acknowledgment with its audit event and rollback when the audit write fails; risk prediction after commit, model fallback, restart recovery, writer-lock retries, and risk alerts; prediction and alert history; schema upgrade of an older database; the OpenAPI document. Frontend: 11 tests run the real app against an in-memory backend that enforces the backend's CSRF, role, and status-code rules (sign-in, session expiry, operator restrictions, acknowledgment, threshold request and approval, user creation, audit filtering).
- **Coverage:** Frontend 93.3% of statements and 82.5% of branches (`npm run test:coverage`). Backend coverage is not measured yet (no coverage plugin configured).
- Latest run on 2026-11-21: 370 backend tests and 42 frontend tests, all passed. The Python clients under `testData/SevenZone_Demo/` check the monitoring prototype against a running backend and are not part of these suites.

```bash
./mvnw test                                   # all backend tests (Windows: .\mvnw.cmd test)
./mvnw test -Dtest=ThresholdApiTest           # one backend test class
cd frontend && npm test                       # all frontend tests
cd frontend && npm run test:coverage          # frontend tests with coverage
cd frontend && npm run typecheck              # TypeScript check
```

## Known Issues

- The dashboard does not yet show each zone's current risk level, calculation time, and source (model or rules); the data is available from `GET /api/readings/latest`. It also computes stale data from the browser clock instead of the backend's `stale` flag.
- There is no admin API to start or end a storage session yet. Sessions are started automatically at startup, ending a session (and resolving or blocking its active alerts) is not possible, and the dashboard always shows each zone's current session.
- An alert stays active when a device stops reporting, because only a new reading can resolve it.
- Readings can be viewed at most 6 hours at a time; there is no paged readings list yet.
- The receipt-to-display time (target: within 5 seconds) has not been measured on the demonstration laptop yet.
- The risk model learns a simulated rule policy, not real spoilage outcomes. Offline training still labels and drops rows using the older gap policy, while the running backend uses the newer per-reading gap handling.
- Stopping the backend by killing the process (for example IntelliJ's stop button on Windows) leaves no shutdown marker, so the next gap is flagged as an unconfirmed interruption.
- The zone-monitoring prototype is a second, separate path with its own database, alerts, and device tokens; the team still has to merge or retire it.
- The default administrator password is public until changed. The `readings` table grows by about 120,000 rows per day of uptime, with no retention policy.
- The frontend production bundle is about 600 kB (mostly the charting library); it is not code-split.

## Team Contributions (Phase 3)

| Member | Contributions This Phase |
|---|---|
| Xiyan Hou | Starting the simulator inside the backend and provisioning devices and storage sessions (`setup/SensorSimulatorRunner`, `DeviceSessionService`); device reading ingestion (`ReadingIngestionService`, `ReadingController`, `DeviceIngestionSecurityConfig`, `DeviceCredentialService`); dashboard query `GET /api/readings/query`; device zones and schema upgrade (`SchemaUpgradeOnStartup`); threshold settings with operator requests and admin approval (`ThresholdService`, `ThresholdController`, `ThresholdProperties`); the `alerts` table and threshold alerts (`ThresholdAlertService`); alert history and acknowledgment (`AlertController`, `AlertQueryService`, `AlertAcknowledgmentService`); risk alerts (`RiskAlertService`); prediction history (`PredictionController`, `PredictionQueryService`); SQLite lock handling (`SqliteLockContentionOverride`). Tests: `ReadingIngestionApiTest`, `ReadingQueryApiTest`, `SensorDataSchemaTest`, `SchemaUpgradeOnStartupTest`, `DeviceSessionServiceTest`, `SensorSimulatorRunnerTest`, `ThresholdApiTest`, `ThresholdServiceTest`, `ThresholdAlertServiceTest`, `AlertSchemaTest`, `AlertApiTest`, `AlertAcknowledgmentServiceTest`, `RiskAlertServiceTest`, `PredictionApiTest`, `SqliteLockContentionTest`. Merged the ML and frontend pull requests; this README. |
| Qizhen Dong | **Java sensor simulator** (`simulator/` package), which sends the sensor readings to the backend: scenario loading with SHT45 frame and 5-second continuity checks (`ScenarioLoader`), the replay loop that stamps each reading with its send time and wraps around at the end of the recording (`ReplayLoop`), HTTP sending with device credentials and retries of the identical payload (`HttpReadingSender`, `DeviceCredentials`), the command line (`SimulatorMain`, `SimulatorOptions`, dry-run and HTTP modes), and the `ScenarioReplay` entry point the backend uses. Tests: `ScenarioLoaderTest`, `ReplayLoopTest`, `HttpReadingSenderTest`, `DeviceCredentialsTest`, `SimulatorMainTest`, `SimulatorOptionsTest`, `ExternalSimulatorEndToEndTest`. **Zone-monitoring prototype** (`monitoring` package, still shipped under the `monitoring-*` profiles): inventory and sensor sources (`SqliteInventorySource`, `JsonlSensorSource`); per-lot condition assessment, live and as of a replay time (`AssessmentEngine`, `AssessmentService`, `AssessmentController`); SHT45 frame decoding with CRC checks (`Sht45Codec`); live telemetry ingestion with HMAC device tokens and its own security filter chain (`LiveTelemetryController`, `LiveTelemetryService`, `LiveTelemetrySecurity`); the 5-second zone alert worker with eight alert types and ordered alert events; accelerated replay of the recorded feed (`RecordedAlertReplayController`); the demo, seven-zone, and live profiles. Tests: `AssessmentEngineTest`, `MonitoringApiTest`, `MonitoringIntegrationTest`, `SevenZoneReplayTest`. |
| Tianyi Yin | Risk prediction (`ml/` package): synthetic scenario generation and quality checks (`RiskScenarioGenerator`, `RiskScenarioQuality`), shared features (`RiskFeatures`), Weka training and evaluation of Random Forest and logistic regression (`ExpandedRiskTrainingMain`, `RiskTrainingMain`), the shipped model with startup checks (`WekaRiskModel`, `WekaRiskPredictor`), the rules fallback (`RiskRules`, `RulesRiskPredictor`), the prediction worker with retries and restart recovery (`RiskPredictionWorker`, `RiskMonitoringRuns`, `PredictionRepository`), `GET /api/readings/latest`, and the ML training report. Tests: `RiskFeaturesTest`, `WekaRiskModelTest`, `WekaRiskPredictorTest`, `RiskScenarioGenerationTest`, `ExpandedRiskTrainingTest`, `RiskBackendIntegrationTest`, `RiskRestartIntegrationTest`, `RiskMonitoringRecoveryTest`, `RiskPredictionPressureTest`. |
| Alexander Li | React + TypeScript dashboard (`frontend/`): sign-in and session handling, zone dashboard with trend charts and stale-data states, alerts with acknowledgment, thresholds with requests and approval, user management, audit review; HTTP client with CSRF handling, polling hook, mock backend for development; 42 Vitest tests; frontend README. |
