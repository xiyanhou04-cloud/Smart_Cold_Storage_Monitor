# Live Sensor Ingestion and Zone Alerts

## Status

The monitoring-live profile implements HTTP ingestion, independent telemetry storage, scheduled zone evaluation, and alert queries. The monitoring-seven-zone profile remains the historical replay. The implemented interface below is authoritative. The later design sections retain extension requirements for real collectors and future integrations.


## Run the implemented live demo

Stop an older server on port 8080 with Ctrl+C. Open PowerShell at the project root and start the backend.

```powershell
$env:APP_TELEMETRY_DEVICE_SECRET = 'local-seven-zone-demo-key'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=monitoring-live"
```

Use Java 21 or newer. If the Windows JBR reports an inability to establish a loopback connection during startup, pass a shorter socket directory as a JVM argument.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=monitoring-live" "-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp"
```

In another PowerShell at the project root, configure the same secret and run the actual HTTP test.

```powershell
$env:APP_TELEMETRY_DEVICE_SECRET = 'local-seven-zone-demo-key'
python -B .\testData\SevenZone_Demo\test_live_telemetry.py
```

Enter the backend administrator password when prompted. Use --username for a different existing user. TELEMETRY_TEST_PASSWORD can supply the password for unattended local testing. The default smoke mode takes about 35 seconds and needs a running monitoring-live backend. It sends eight five-second ticks across seven devices and asserts the 56 persisted readings, all eight alert types, exactly one opening and closing event per incident, channel isolation, credential rejection, duplicate handling and conflicting identity rejection. Each tick prints active zone alerts from the latest backend evaluation, which may still be processing the just-uploaded readings. The final summary prints opening and closing times from the backend. An empty alert list means no active zone alerts at query time. Product-level compliance requires separate lot assessments. Avoid running another simulator against these same device IDs during the smoke test.

To stream the full fixture at its actual five-second cadence, pass --mode stream. The client sends all 2,527 frames over 30 minutes. The existing offline replay scripts can still check interval rules independently.

## Implemented HTTP and storage details

- POST /api/telemetry/readings uses X-Device-Id and X-Device-Token. The token is the lowercase hex HMAC-SHA256 of the device ID using the configured demo secret. Derive individual tokens for device clients. Browser login alone grants no access to the ingestion route.
- The demo accepts explicitly synthetic envelopes with bootId, sequence, measuredAt, synthetic, temperature, humidity and optional rawFrameHex. Use state PRESENT, MISSING or INVALID. The server maps the authenticated device to its configured zone. Identical retries return 200, new samples return 201, and conflicting retries return 409.
- GET /api/telemetry/samples?bootId=... returns persisted samples for a run. GET /api/monitoring/alerts?state=OPEN returns current incidents. Use state=CLOSED or state=ALL for history. GET /api/monitoring/alert-events?afterId=... returns ordered OPENED and CLOSED events. These GET routes use the existing browser login session. Each accepts a limit from 1 to 500.
- Telemetry is stored in data/live-telemetry.sqlite by default. The database has sensor_samples, alerts and alert_events tables. A sample retains the original serialized envelope along with normalized channel states and receipt and acquisition times. An alert transition and its event commit together.
- The worker evaluates the newest sample for each configured zone every five seconds. It reads operating limits at startup. It accepts a sample at exactly 15 seconds of age and detects absence on the first tick after that. After a run stops, enabled devices will eventually produce missing-channel alerts. The historical replay and existing per-lot API retain their separate data path. This milestone exposes live zone alerts through the endpoints above.

## Data flow

Seven simulated devices -> authenticated ingestion API -> telemetry SQLite -> five-second alert worker -> alert API -> frontend.

Use one device per location from the seven-zone mapping. Keep the inventory SQLite read-only in this flow. It supplies location operating limits and lot requirements. Use an independent writable telemetry SQLite for sensor samples, evaluation progress and alert events.

The simulator replays all seven traces on a common five-second cadence. Use a monotonic timer to avoid accumulated scheduling drift. At each tick send one authenticated request per device with a common sampling instant. Rebase the source timestamps to the run start, allocate a new boot ID per device and preserve the sequence through retries. Retain the original fixture time as optional provenance. Keep synthetic=true and use a clock status reserved for the explicitly enabled live demo. The simulator stops after the 361st tick in the default 30-minute run. The collector and evaluator continue running until stopped separately. A deliberately stopped demo device can be disabled at the end if offline alerts are unwanted.

## Proposed ingestion contract

POST /api/telemetry/readings accepts one sample per request. Use a scoped device credential and derive the device identity and zone mapping on the server. A browser session cannot authorize device ingestion. Preserve CSRF for browser-authenticated mutations and isolate the machine route in security configuration. Enable synthetic devices only in the live simulation profile.

Example JSON. The values illustrate a simulated independent channel failure.

```json
{
  "bootId": "demo-20261107-001",
  "sequence": 42,
  "measuredAt": "2026-11-07T21:03:30Z",
  "synthetic": true,
  "temperature": {"value": 3.1, "state": "PRESENT"},
  "humidity": {"value": null, "state": "MISSING"}
}
```

A channel state is PRESENT, MISSING or INVALID. Allow a bounded diagnostic code and optional rawFrameHex. The backend derives final channel states. A simulator may send a numeric value without a raw frame in the demo profile. A physical collector can submit the raw frame so the backend can check each channel independently. The existing Sht45Codec requires both CRCs to pass. Add a channel-level decode path for ingestion and preserve existing replay behavior. Check raw humidity encoding according to the device protocol and apply its defined clamping policy. A numeric RH supplied as an already normalized value must be within 0 to 100. Avoid treating an encoding artifact as a physical observation. Validate temperature against the configured sensor capability range. Use separate zone limits to detect environmental excursions.

A missing field or explicit null is MISSING. A present but unparseable, non-finite, CRC-failed or implausible channel is INVALID. A valid sibling channel remains available for threshold checks. Standard JSON cannot carry NaN or infinity as numeric literals. Reject malformed JSON as a request error. Use a valid envelope with state INVALID and a diagnostic code to represent such faults in the simulator. Persist valid envelopes carrying missing or invalid channels and retain the raw diagnostics. Invalid request identity, unauthorized devices and structurally invalid envelopes are rejected before sample storage.

Store server receivedAt separately from measuredAt. Validate clock trust and future skew. Samples with unusable time remain available for diagnostics and are excluded from environmental assessment. Reject a conflicting payload that reuses an existing device, boot and sequence identity. Retrying the same payload returns the original result. Retries retain the original acquisition time and identity and never refresh the sample's liveness timestamp.

## Proposed telemetry database

| Table | Fields and constraints |
| --- | --- |
| sensor_samples | id, device_id, boot_id, sequence, zone_code, measured_at, received_at, synthetic, clock_status, raw_frame_hex, temperature_c nullable, temperature_state, temperature_reason nullable, humidity_pct nullable, humidity_state, humidity_reason nullable. Unique device_id plus boot_id plus sequence. Index by device and acquisition time. |
| evaluation_state | location_code, device_id, last processed sample cursor, configured monitoring start, last accepted receipt and acquisition time. Persist worker progress. |
| alerts | id, location_code, device_id, type, state, opened_at, last_seen_at, closed_at nullable, close_reason nullable, sample_id nullable, observed_value nullable, threshold nullable. Enforce one OPEN incident per location, device and type. |
| alert_events | Monotonic event ID, alert_id, event_type, occurred_at and source sample ID where applicable. Open and close transitions are inserted with the alert state in the same transaction. |

Normalize timestamps to UTC. Retain malformed channel evidence with nullable numeric fields and diagnostic codes. Use short SQLite write transactions. Serialize or guard concurrent updates with uniqueness constraints. Keep old samples for audit and define a retention period before a long-duration deployment.

## Five-second evaluation and alert types

Run one non-overlapping scheduled evaluation every five seconds. Evaluate all monitored zones against a common tick time. For each zone, fetch the newest usable sample at or before that time. Guard against older or late samples replacing a newer current reading. An active device without a fresh sample generates absence findings once its grace period expires. Use a configurable 15-second acquisition freshness and startup grace as the initial demo default. A fresh packet with a null channel is an explicit missing-channel finding at the next evaluation tick. Reusing the same sample on subsequent ticks keeps the incident open without adding a duplicate event. A future refinement may process intermediate samples for brief excursions between ticks.

| Alert type | Trigger |
| --- | --- |
| ZONE_TEMPERATURE_LOW | Valid temperature is strictly below the zone operating minimum. |
| ZONE_TEMPERATURE_HIGH | Valid temperature is strictly above the zone operating maximum. |
| ZONE_HUMIDITY_LOW | Valid RH is strictly below the zone operating minimum. |
| ZONE_HUMIDITY_HIGH | Valid RH is strictly above the zone operating maximum. |
| SENSOR_TEMPERATURE_MISSING | Fresh packet has no temperature, or no fresh usable sample is available after the grace period. |
| SENSOR_HUMIDITY_MISSING | Fresh packet has no humidity, or no fresh usable sample is available after the grace period. |
| SENSOR_TEMPERATURE_INVALID | Fresh temperature channel is present but fails decode, CRC or plausibility validation. |
| SENSOR_HUMIDITY_INVALID | Fresh humidity channel is present but fails decode, CRC or plausibility validation. |

Record separate absence reasons for an explicit missing field, a stale or silent stream and an untrusted clock. An offline device can open both missing-channel incidents. The frontend can group them visually by device. For each channel, classify quality first. Compare a valid value only against configured bounds. Equal-to-limit values are in range. A null bound skips that direction. A plausible value outside the zone limits is an environmental alert. A physically implausible value is a sensor alert. Continue evaluating a valid sibling channel independently.

Open immediately on the first failing evaluation for the course demo. Keep one incident per type while the condition persists and update last_seen_at when new evidence arrives. Close with RECOVERED when a valid observation confirms that the corresponding condition ended. When a channel becomes unavailable during an environmental excursion, retain the open excursion and mark its evidence unknown. A missing or invalid reading does not confirm environmental recovery. If an invalid incident is superseded by a missing channel, close the invalid incident with SUPERSEDED and open the missing incident. Apply the reverse when a missing channel returns as invalid. When a valid channel changes directly from low to high, close the low incident and open the high incident in the same evaluation.

The zone worker uses locations operating bounds. Existing lot assessments continue to use product limits. A room-level excursion and a lot-level failure can legitimately differ. An expected detection time for a fresh reading is the next five-second evaluation plus frontend delivery time. The 15-second absence grace is evaluated on the same scheduler.

## Frontend contract

- GET /api/monitoring/alerts?state=OPEN returns current incidents with zone code, device ID, type, observed value, applicable threshold and UTC timestamps.
- GET /api/monitoring/alert-events?afterId=... returns ordered OPENED and CLOSED events after the supplied cursor. Initially poll every five seconds. For lower delivery latency, optionally add authenticated SSE backed by the same durable event cursor.
- GET /api/monitoring/assessments remains the existing lot-level contract. In the live simulation profile, supply current readings from the telemetry database to the existing assessment layer. Continue labeling synthetic readings in its response.

Use the same incident ID across repeated observations. Refresh active alerts after reconnect and use the event cursor for history. Display acquisition time and opening time separately. Use the zone code to join to the location catalog. An alert event is a backend record that can drive UI indicators. Notification delivery is a separate integration.

## Acceptance scenarios

- Seven devices send 361 samples each over 30 minutes. The database stores exactly 2,527 samples for the complete fault-free fixture. Replaying the same boot and sequence identities leaves the count unchanged.
- Inject all eight alert types through fixture overrides. Use a plausible value just outside a zone bound for excursion tests, a valid envelope with null for missing channels and a valid envelope with a bad CRC or implausible value for invalid channels.
- Stop one device without stopping the simulator. Both channel absence findings appear after the grace period. Other zones continue to evaluate.
- Verify one open incident through repeated bad samples, one recovery event after valid in-range data, no recovery on missing evidence and no false reopen on late historical input.
- Verify that a broken temperature channel still permits a humidity alert and vice versa.
- Verify that reload or retry does not create duplicate events, device credentials cannot spoof another zone, and the original seven-zone historical replay still works.

## Accelerated backend replay of the recorded feed

Restart the backend with the monitoring-live profile after building the new code. Run the following in the second PowerShell window from the repository root.

```powershell
python -B .\testData\SevenZone_Demo\test_recorded_live_replay.py
```

Enter a speed multiplier and the backend account password. At 10x, five recorded seconds take 0.5 wall-clock seconds. The 30-minute fixture takes about three minutes at 10x or 18 seconds at 100x. Use --speed for unattended runs.

The authenticated GET /api/monitoring/recorded-alert-replay?speed=10 route reads the configured sensor file. It emits newline-delimited JSON with one TICK after each evaluation and a final COMPLETE record. TICK includes the recorded timestamp, per-zone readings and their quality, active alerts, alert transitions and cumulative sample counts. COMPLETE confirms that the backend reached the final recorded instant. The client prints every tick, including ticks without active alerts.

The backend evaluates with recorded UTC timestamps and the same zone alert rules as live monitoring. Replay state is isolated in a temporary in-memory SQLite database. The device ingestion endpoint and production alert history remain independent. This test verifies recorded sensor processing and live delivery of replay results over HTTP. Use test_live_telemetry.py to test device HTTP ingress and persistent telemetry writes. The replay reports zone conditions. Lot-level assessments use separate product rules.

The seven-zone recording contains 361 ticks and 2,527 samples. Each replay processes every record in the file. The endpoint validates record identity before emitting results and accepts one concurrent replay per server.
