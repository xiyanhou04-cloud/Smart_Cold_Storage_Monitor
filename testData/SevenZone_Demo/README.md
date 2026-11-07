# Seven-Zone Replay Fixture

This fixture extends the five-zone inventory and preserves its sensor samples. The seven-zone profile adds two physical fruit locations and four positive-stock lots. It uses the existing inventory schema and assessment API. The broader zone catalog and versioned profile model remain design work.

| Location code | Device ID | Representative stock | Target C | Target %RH |
| --- | --- | --- | --- | --- |
| RAW-MEAT | ESP32-SIM-001 | Chilled raw beef and chicken | 0 to 4 | 55 to 80 |
| VEG-COLD | ESP32-SIM-002 | Leafy greens, broccoli and other cold produce | 0 to 2.22 | 95 to 98 |
| VEG-COOL | ESP32-SIM-003 | Cucumbers | 10 to 12.22 | 90 to 95 |
| EGGS | ESP32-SIM-004 | Shell eggs | 0 to 4 | 55 to 80 |
| FROZEN | ESP32-SIM-005 | Frozen raw chicken and beef | -25 to -18 | 35 to 80 |
| FRUIT-COLD-BERRY | ESP32-SIM-006 | Fresh blueberries | -0.5 to 0.5 | 90 to 95 |
| FRUIT-TROPICAL | ESP32-SIM-007 | Mature-green Cavendish bananas | 13 to 14 | 90 to 95 |

The table lists location operating targets. See the current integration contract for simulated humidity and lower-bound provenance in the five inherited zones. The fruit references are the [UC Davis bushberry guide](https://postharvest.ucdavis.edu/produce-facts-sheets/bushberry) and [UC Davis Cavendish banana guide](https://postharvest.ucdavis.edu/produce-facts-sheets/banana). The new lot dates, transactions and quantities are synthetic.

## Files and time axis

- monitoring_timestamped.jsonl contains 2,527 SHT45 samples. Each of seven devices reports 361 samples, one per five-second UTC tick from 2026-11-05T17:00:00Z through 17:30:00Z inclusive.
- cold_storage_demo.sqlite contains 7 locations, 14 products and 28 lots, with independent inventory records.
- database_dump.sql reconstructs the expanded SQLite inventory.
- The profile application-monitoring-seven-zone.yaml selects both fixtures and defines the authoritative location-to-device mapping.

Samples use the existing JSONL schema with assigned synthetic UTC acquisition times. The receipt time is null. Each frame contains two CRCs and two decoded measurements. Join the source to inventory through the profile's device mapping and location code. The feed's location_code field is descriptive metadata.

## Run and validate

From the repository root, start the application with this PowerShell command.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=monitoring-seven-zone"
```

Authenticate as described in the [integration contract](../../docs/STORAGE_ZONE_INTEGRATION.md). Query GET /api/monitoring/replay/assessments?at=2026-11-05T17:10:00Z. Group by environmentStatus for 28 lots. Expect 18 OK, 10 ACTION_REQUIRED and zero UNKNOWN. At that instant six raw meat lots exceed their temperature limit, two cucumber lots fall below their humidity limit and two banana lots fall below their temperature limit. The berry lots remain within range. The banana trace starts in range, drops below the limit and later recovers. Use the REPLAY mode for these synthetic observations.

## Fast alert interval replay

Run from the repository root with Python 3.

```powershell
python .\testData\SevenZone_Demo\replay_zone_alerts.py
```

The offline runner advances the simulated clock by five seconds without sleeping. It reads zone operating limits from the fixture database, validates the sample channels and prints CSV to standard output. replay_alert_intervals.csv records the result for the supplied fixture. Times describe simulated detection ticks. A closed interval ends at the first evaluation that confirms the condition ended. Open intervals have blank close and duration fields. The fixture includes threshold excursions and near-limit crossings. Add fault samples to exercise missing and invalid channel cases. This offline runner tests interval behavior. HTTP ingestion, scheduler timing and frontend delivery require an end-to-end test of the live implementation.

### Run the regression test program

From the repository root, run:

```powershell
python -B .\testData\SevenZone_Demo\test_zone_alert_replay.py
```

The program checks the supplied 30-minute fixture against the expected CSV and uses short synthetic scenarios to verify all eight alert types, deduplication, recovery, inclusive bounds, silence, channel isolation and validation. A passing run prints the alert intervals. A failing run reports the differences and exits with code 1. It uses the Python standard library and runs independently of the backend process.

## Live HTTP smoke test

See [Live Sensor Ingestion and Zone Alerts](../../docs/LIVE_TELEMETRY_AND_ALERTS.md) to launch the monitoring-live backend and run test_live_telemetry.py. That test exercises HTTP ingestion, persisted readings, scheduled alerts and recovery in approximately 35 seconds.

## Accelerated recorded alert test against the backend

Run python -B ./testData/SevenZone_Demo/test_recorded_live_replay.py from the repository root while the updated monitoring-live backend runs. Enter a playback multiplier. The program prints zone values and alert transitions on every tick and verifies the completion counts. See [Live Sensor Ingestion and Zone Alerts](../../docs/LIVE_TELEMETRY_AND_ALERTS.md) for the streaming contract.
