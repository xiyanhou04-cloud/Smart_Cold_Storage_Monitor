# Core Storage Condition Assessment

See [Live Sensor Ingestion and Zone Alerts](LIVE_TELEMETRY_AND_ALERTS.md) for the next milestone contract.

See [Extensible Cold Storage Zoning](COLD_STORAGE_ZONING_DESIGN.md) for the proposed catalog, storage profiles, segregation rules, and integration roadmap.

See [Storage Zones and Integration Contract](STORAGE_ZONE_INTEGRATION.md) for zone targets, device mappings, and frontend and inventory fields.

## Boundaries and flow

Manual inventory SQLite -> InventorySource -> AssessmentService <- SensorSource <- independent JSONL -> AssessmentEngine -> authenticated REST response.

The application authentication/audit database, manual inventory database and sensor stream remain independent. Assessment never changes stock, product requirements, audit records or raw sensor data. SQLite inventory connections use mode=ro and query_only=ON. Manual inventory editing and sensor collection are separate upstream responsibilities.

InventorySource and SensorSource are replaceable adapter interfaces. The first implementation reads the supplied external SQLite schema and completed JSONL samples. The schema uses demo_use_by_at because the supplied inventory dates are fictional. A production adapter can map a genuine supplier use-by field without changing the engine.

## Running

Use Java 21 or later and run from the repository root:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=monitoring-demo"
```

Existing session login, CSRF and roles continue to apply. Authenticate through /api/auth/csrf and /api/auth/login, then use the session cookie for the following GET endpoints:

- /api/monitoring/assessments: LIVE, evaluates at the current server UTC clock.
- /api/monitoring/replay/assessments?at=2026-11-05T17:10:00Z: REPLAY, evaluates at an explicit ISO 8601 instant. Timezone offsets must be URL encoded if they contain a plus sign.

Each request reloads independent source snapshots and evaluates all positive-stock lots. A dashboard can poll every two seconds. Appended sensor records and committed manual inventory transactions are observed on the next request. This is request-driven current assessment, not a background alarm scheduler or a 24/7 hardware acquisition service.

The monitoring-demo profile maps five independent synthetic devices to all five warehouse locations. Each UTC sample instant includes one frame per zone. The original raw meat curve is preserved. Default configuration maps no locations. Never share one device across unrelated physical zones. Configure app.monitoring.location-devices using actual location codes and devices before live use.

## Assessment semantics

Each lot exposes its SKU, product, quantity, unit, location, use-by, quality hold, configured limits and source URL. Lots of the same SKU are evaluated separately because location, receipt, hold and expiry can differ. Quantities from different units are never combined.

Inventory quantity is reconstructed using movements with operated_at at or before the evaluation instant; lots with no positive stock are excluded. Replay uses current product requirements, lot metadata and mappings: it does not reconstruct historical edits to those attributes because the provided schema does not version them. Inventory-source failure returns HTTP 503 rather than an empty successful inventory.

- OK: all configured environmental checks pass with trusted current input.
- WARNING: attention is needed, such as impending expiry, clock holdover, unspecified humidity or simulated replay provenance.
- ACTION_REQUIRED: temperature/humidity violates product bounds, use-by is reached, or quality hold applies.
- UNKNOWN: data or requirements are insufficient. Missing mapping, missing/stale input, pre-receipt input, unsynchronized clock and simulated input in LIVE mode cannot produce an environmental pass.

Exact threshold values are in range. Null bounds remain unspecified; no arbitrary zero limits are inserted. Missing humidity is explicitly reported as not assessed. Overall status prioritizes ACTION_REQUIRED, then UNKNOWN, then WARNING, then OK; environmentStatus remains separate so an expired lot cannot hide missing sensor coverage. All findings are retained.

These checks identify condition violations, not biological spoilage or certified food safety. The fixture has 1,805 sensor frames. At 17:10:00Z, raw meat is above its temperature limit and the cucumber zone is below its humidity limit. Product humidity settings for the meat, egg and freezer zones are explicitly simulation-only. Structured shelf-life values and their provenance are available through the inventory SQL queries. No trained ML model, exposure-duration risk model, alert acknowledgment workflow or automated stock disposal is implemented.

## Sensor contract and validation

The sensor JSONL adapter requires deviceId, device_session_id, sequence_number, measured_at, frame_hex, temperatureC, humidityPct, synthetic, clock_sync_status and time_source; received_at may be null. Sequence is a nonnegative integer. Timestamps are parseable UTC instants or explicit offsets. Valid clock states are SYNCED, HOLDOVER, UNSYNCED and SIMULATED_ALIGNED.

Six-byte SHT45 frames are decoded and both CRC-8 values checked. Decoded physical values must match the declared values within 0.01. Invalid records, duplicate identities and malformed partial lines are rejected and counted in sourceIssues/rejectedSensorRecords. The next request retries an appended line. Future readings are excluded when selecting the latest sample at the evaluation time. The selected sample must be no older than stale-after (30 seconds by default) and must not predate the lot receipt.

A fresh LIVE reading must be non-synthetic with a SYNCED or HOLDOVER clock. The provided November 5 sample is synthetic and will never silently become a live pass. REPLAY explicitly permits synthetic aligned timestamps and includes a provenance warning. received_at is retained separately from acquisition time; it is not substituted for measured_at.

The adapter reads a bounded file snapshot (10 MB default). It is suitable for this prototype, not unlimited 24/7 archive growth. Rotate the active feed or implement an indexed SensorSource for larger installations. Sensor-source failure leaves inventory visible with UNKNOWN environment status and source diagnostics. JSONL integrity is not device authentication: only a trusted collector should write the file.

SHT45 hardware has no clock. A future collector must synchronize ESP32 time through SNTP/NTP, schedule with a monotonic clock, persist samples during outages and mark HOLDOVER/UNSYNCED appropriately. No network device-ingestion endpoint or firmware change is included in this core implementation.

## Tests

Coverage includes inclusive limits, separate humidity and temperature violations, one-sided frozen limits, stale/missing data, receipt-time boundaries, held/expired lots, synthetic/live separation, holdover, CRC errors, duplicate and partial records, append visibility, as-of stock reconstruction, read-only database integrity, missing sources, replay without future data, session protection and invalid timestamp handling. Existing authentication and account-management tests are also run.
