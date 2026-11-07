# Smart Cold Storage Monitor

Phase 2 provides inventory assessments, seven-zone telemetry ingestion, scheduled alerts, and accelerated replay of recorded sensor data. The backend uses Java 21 or later, Spring Boot, and SQLite. The device simulators and HTTP test clients use Python 3 and its standard library.

## Start and verify

From the repository root in PowerShell, run the automated tests:

```powershell
.\mvnw.cmd test
```

Start the live simulation backend in one window:

```powershell
$env:APP_TELEMETRY_DEVICE_SECRET = 'local-seven-zone-demo-key'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=monitoring-live"
```

In another window, run either backend test. Use the local administrator credentials when prompted.

| Test | Command | Verification |
| --- | --- | --- |
| Live HTTP smoke test | python -B ./testData/SevenZone_Demo/test_live_telemetry.py | Supply the same APP_TELEMETRY_DEVICE_SECRET in this window. Seven devices send 56 samples in about 35 seconds. The test checks persistence and all eight alert types. |
| Accelerated recorded replay | python -B ./testData/SevenZone_Demo/test_recorded_live_replay.py | Enter a speed. The backend reads all 2,527 recorded samples across 361 ticks and streams zone conditions and alert transitions. At 100x the playback takes about 18 seconds. |

Run the two tests separately for a clear demonstration. See [live telemetry and alert interfaces](docs/LIVE_TELEMETRY_AND_ALERTS.md) for endpoint contracts, configuration, authentication, and Windows startup troubleshooting.

## Data and integration

- The seven-zone sample includes [inventory SQLite](testData/SevenZone_Demo/cold_storage_demo.sqlite) and [recorded sensor JSONL](testData/SevenZone_Demo/monitoring_timestamped.jsonl). The five-zone fixture remains available for the default configuration and regression tests.
- The live server writes its own telemetry database under data/. Account data also lives under data/. Both runtime databases and Maven output under target/ are local files excluded from Git.
- The backend evaluates zone alerts against location limits. Inventory assessments use the separate product limits. The accelerated alert replay runs in its own temporary database.
- The [architecture guide](docs/MONITORING_ARCHITECTURE.md) and [storage integration contract](docs/STORAGE_ZONE_INTEGRATION.md) describe the existing interfaces. The [zoning design](docs/COLD_STORAGE_ZONING_DESIGN.md) identifies future schema and API proposals.
