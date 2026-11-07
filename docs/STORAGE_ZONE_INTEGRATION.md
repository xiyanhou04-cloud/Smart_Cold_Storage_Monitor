# Storage Zones and Integration Contract

See [Extensible Cold Storage Zoning](COLD_STORAGE_ZONING_DESIGN.md) for the proposed catalog, storage profiles, segregation rules, and integration roadmap.

## Demo zone catalog

Use the location code as the stable join key and the English label for display. The operating targets below come from the demo inventory database. The application profile binds one simulated device to each location.

| Location code | Display label | Representative products | Zone temperature (C) | Zone humidity (%RH) | Demo device |
| --- | --- | --- | ---: | ---: | --- |
| VEG-COLD | Cold Humid Produce Zone | Broccoli, carrots, cabbage, cauliflower, lettuce | 0 to 2.22 | 95 to 98 | ESP32-SIM-002 |
| VEG-COOL | Cool Cucumber Zone | Cucumbers | 10 to 12.22 | 90 to 95 | ESP32-SIM-003 |
| RAW-MEAT | Separate Raw Meat Chiller | Chilled raw beef steaks, raw chicken pieces, raw ground beef | 0 to 4 | 55 to 80 | ESP32-SIM-001 |
| EGGS | Separate Egg Chiller | Raw shell eggs | 0 to 4 | 55 to 80 | ESP32-SIM-004 |
| FROZEN | Freezer Zone | Frozen raw chicken pieces, frozen raw ground beef | -25 to -18 | 35 to 80 | ESP32-SIM-005 |

Display values for produce are rounded. Use the stored database values for calculations. The produce product humidity bands are reference values. The meat, egg and freezer humidity bands and zone temperature lower bounds are simulation settings. Product temperature limits in those three zones are upper bounds only. The assessment engine evaluates each lot against its product limits, while the zone targets describe the room's operating range.

## Inventory database

The demo SQLite file is [cold_storage_demo.sqlite](../testData/ColdStorage_Demo/cold_storage_demo.sqlite). See [schema.sql](../testData/ColdStorage_Demo/schema.sql) and [queries.sql](../testData/ColdStorage_Demo/queries.sql).

| Table | Integration fields |
| --- | --- |
| locations | id, code, name_en, temp_min_c, temp_max_c, rh_min_pct, rh_max_pct, purpose |
| products | id, sku, name_en, category, unit, temp_min_c, temp_max_c, rh_min_pct, rh_max_pct, condition_note, source_id |
| product_storage_guidance | product_id, shelf_life_min, shelf_life_max, shelf_life_unit, shelf_life_basis, humidity_basis |
| lots | id, lot_code, product_id, location_id, supplier_id, packed_at, received_at, demo_use_by_at, quality_status, packaging, notes |
| movements | lot_id, operation, quantity, operated_at, recorded_at, operator_id, reference_no |
| sources | id, title, url, scope_note, accessed_on |

Join lots.location_id to locations.id, lots.product_id to products.id, product_storage_guidance.product_id to products.id, and products.source_id to sources.id. Shelf-life units are days, weeks or months. The shelf-life basis is REFERENCE or QUALITY_ONLY. Humidity provenance is REFERENCE or SIMULATION_ONLY. Keep calendar months as months. Use the explicit lot demo_use_by_at for the lot countdown. All demo lot dates are synthetic. Treat NULL bounds as unspecified and preserve one-sided ranges. Threshold endpoints are inclusive.

For a current stock list, v_inventory provides quantity_on_hand by lot. For historical replay, the backend sums IN and OUT movements through the evaluation instant and includes positive-stock lots only. Use v_movement_history for operator-facing movement details. The monitoring backend opens this database read-only. Sensor frames and timestamps remain in the separate sensor feed.

## Sensor and device mapping

The active demo mapping is in [application-monitoring-demo.yaml](../src/main/resources/application-monitoring-demo.yaml) under app.monitoring.location-devices. Its keys are locations.code and its values are sensor device IDs. The JSONL location_code is descriptive metadata. The backend uses the configured mapping to route device readings to lots.

The [combined JSONL feed](../testData/SHT45_30min/monitoring_timestamped.jsonl) contains one sample per zone every five seconds from 2026-11-05T17:00:00Z through 17:30:00Z inclusive. Each device has 361 records. The feed contains 1,805 records in total.

| Source fields | Meaning |
| --- | --- |
| deviceId, device_session_id, sequence_number | Device, boot session and sample identity for deduplication |
| measured_at, received_at, elapsed_seconds | Acquisition instant, optional receipt instant and offset within the fixture |
| frame_hex, temperatureC, humidityPct | Six-byte SHT45 response and decoded engineering values |
| synthetic, clock_sync_status, time_source | Provenance and clock trust |
| location_code | Optional descriptive demo label |

Use UTC instants for transport and convert them to local time at display time. The replay feed uses assigned synthetic timestamps and null receipt times. The backend validates both frame CRCs and checks decoded values against the supplied engineering values.

## Frontend API

- GET /api/auth/csrf supplies the session CSRF token. POST /api/auth/login accepts form fields username and password. Send the CSRF header and keep the session cookie for monitoring requests.
- GET /api/monitoring/assessments evaluates current stock and current sensor data. The response mode is LIVE.
- GET /api/monitoring/replay/assessments?at=2026-11-05T17:10:00Z evaluates the supplied instant. The response mode is REPLAY. URL-encode the timestamp query value when building the request.

The report contains evaluatedAt, generatedAt, mode, ruleVersion, inventorySource, sensorSource, sourceIssues, rejectedSensorRecords and lots. Group lots by lot.location. Use lot.id or lot.code as the row key and lot.sku as the product key.

| Response fields | Frontend use |
| --- | --- |
| lot.product, lot.sku, lot.code, lot.quantity, lot.unit | Product and lot identity and inventory quantity |
| lot.location, deviceId | Join to the zone catalog and identify the configured device |
| lot.tempMin, lot.tempMax, lot.rhMin, lot.rhMax | Product-level limits in C and %RH |
| lot.receivedAt, lot.useBy, lot.qualityStatus | Receipt time, configured cutoff and lot hold status |
| lot.conditionNote, lot.sourceUrl | Storage guidance and its reference |
| reading.temperatureC, reading.humidityPct, reading.measuredAt | Sensor values and acquisition time. The entire reading can be null |
| reading.receivedAt, reading.synthetic, reading.clockStatus, reading.timeSource | Receipt time and provenance |
| environmentStatus, status, findings | Environmental result, overall lot result and individual reasons |
| temperatureSpecified, humiditySpecified | Whether the corresponding product has at least one configured bound |

Statuses are OK, WARNING, ACTION_REQUIRED and UNKNOWN. Use environmentStatus for the sensor condition badge and status for the overall lot badge. A lot hold or expiry can require action independently of its environmental result. Render missing readings as unavailable. Findings expose code, severity and message. Common codes include TEMPERATURE_OUT_OF_RANGE, HUMIDITY_OUT_OF_RANGE, QUALITY_HOLD, EXPIRING_SOON, USE_BY_REACHED, NO_DEVICE_MAPPING, NO_READING, STALE_OR_FUTURE_READING and SIMULATED_REPLAY.

For a zone selector or dashboard that shows every zone even when empty, use the catalog in this document for the demo or query locations on the backend. The current API returns positive-stock assessments. Zone labels, zone operating targets, structured shelf-life guidance and humidity provenance are available through the inventory database. The current response exposes product bounds and condition notes. A frontend that needs those catalog fields dynamically requires a separate backend contract.

The current app evaluates on request. A dashboard can poll the live endpoint and show each sample's measuredAt. Handle HTTP 401 by restoring the session, HTTP 400 by correcting replay input, and HTTP 503 by showing inventory source unavailability. Render sourceIssues and rejectedSensorRecords as sensor diagnostics when the report itself succeeds.

## Replay smoke check

Start the backend with the monitoring-demo profile. At 2026-11-05T17:10:00Z the replay returns 24 lots with readings. Six raw meat lots exceed their temperature limit and two cucumber lots fall below their humidity limit. Sixteen lots pass the environmental checks. Use environmentStatus for these counts. Overall status can also reflect expiry, holds and simulation provenance.

See [Monitoring Architecture](MONITORING_ARCHITECTURE.md) for startup commands and rule details.
