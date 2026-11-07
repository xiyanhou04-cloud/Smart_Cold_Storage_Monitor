# Independent Sensor Stream and Clock Contract

This directory stores five independently generated, UTC-aligned sensor traces apart from manual inventory records. monitoring_timestamped.jsonl is a 30-minute test excerpt: 361 readings per zone at five-second intervals, 1,805 total, from 2026-11-05T17:00:00Z to 17:30:00Z. This corresponds to 09:00-09:30 America/Los_Angeles (UTC-08:00).

The origin is assigned simulation time, not real acquisition time or measured NTP synchronization. synthetic=true, time_source=ASSIGNED_SIMULATION_ORIGIN and clock_sync_status=SIMULATED_ALIGNED make this explicit. clock_offset_ms and received_at are null because no actual clock offset or network receipt was measured.

measured_at is UTC acquisition time; elapsed_seconds is relative time. deviceId, device_session_id and sequence_number identify the device, boot session and sample. These are not inventory session identifiers. SHT45 six-byte responses contain no timestamps; the microcontroller must add them.

Future 24/7 firmware should synchronize via SNTP/NTP at startup and periodically, schedule sampling with a monotonic clock, timestamp each acquisition in UTC, cache during outages and upload later. A server should independently set received_at and deduplicate by device, boot session and sequence. Mark unsynchronized clocks UNSYNCED and previously synchronized offline clocks HOLDOVER; preserve uptime and sequence. Record gaps, resets and clock corrections explicitly. Do not substitute receipt time for acquisition time.

The supplied Arduino sketch is still a finite 30-minute replay and stops at the end. Actual continuous collection and NTP firmware are not implemented. Do not loop old timestamps and call that continuous monitoring. The baseline curve is a test scenario, not valid storage conditions for all inventory zones.

The original decoded_readings.jsonl and binary frames have only relative timing or no timing. The timestamped JSONL is an exchange example, not an exact backend API request; map measured_at to timestamp and configure the device/session before integration.
