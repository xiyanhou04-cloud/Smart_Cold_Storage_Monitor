"""Evaluate zone alert intervals on the fixture clock without waiting."""
import csv
import json
import math
import sqlite3
import sys
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STEP = timedelta(seconds=5)
FRESHNESS = timedelta(seconds=15)
CHANNELS = ("TEMPERATURE", "HUMIDITY")


def utc_text(instant):
    return instant.isoformat().replace("+00:00", "Z") if instant else ""


def crc(word):
    result = 0xFF
    for byte in word:
        result ^= byte
        for _ in range(8):
            result = ((result << 1) ^ (0x31 if result & 0x80 else 0)) & 0xFF
    return result


def read_channel(row, channel, frame):
    field = "temperatureC" if channel == "TEMPERATURE" else "humidityPct"
    value = row.get(field)
    if value is None:
        return "MISSING", None
    limits = (-40, 125) if channel == "TEMPERATURE" else (0, 100)
    if type(value) not in (int, float) or not math.isfinite(value) or not limits[0] <= value <= limits[1]:
        return "INVALID", None
    if frame is not None:
        offset = 0 if channel == "TEMPERATURE" else 3
        if len(frame) != 6 or crc(frame[offset:offset+2]) != frame[offset+2]:
            return "INVALID", None
        raw = int.from_bytes(frame[offset:offset+2], "big")
        decoded = -45 + 175*raw/65535 if offset == 0 else max(0, min(100, -6 + 125*raw/65535))
        if abs(decoded - value) > 0.01:
            return "INVALID", None
        value = decoded
    return "VALID", value


def load_samples(zones):
    samples = []
    devices = {}
    observed = set()
    with (ROOT / "monitoring_timestamped.jsonl").open(encoding="utf-8") as source:
        for line in source:
            row = json.loads(line)
            zone = row["location_code"]
            device = row["deviceId"]
            instant = datetime.fromisoformat(row["measured_at"].replace("Z", "+00:00"))
            if zone not in zones or devices.setdefault(zone, device) != device:
                raise ValueError(f"Inconsistent zone mapping: {zone}")
            if instant.utcoffset() != timedelta(0) or (zone, instant) in observed:
                raise ValueError(f"Invalid or duplicate zone timestamp: {zone} {instant}")
            observed.add((zone, instant))
            raw = row.get("frame_hex")
            try:
                frame = bytes.fromhex(raw) if raw is not None else None
            except (ValueError, TypeError):
                frame = b""
            samples.append((instant, zone, [read_channel(row, c, frame) for c in CHANNELS]))
    if len(set(devices.values())) != len(devices) or not samples:
        raise ValueError("Expected a nonempty feed with independent devices.")
    return sorted(samples, key=lambda sample: (sample[0], sample[1]))


def replay(zones, samples):
    start, end = samples[0][0], samples[-1][0]
    tick = start
    index = 0
    latest = {}
    active = {}
    intervals = []
    while tick <= end:
        while index < len(samples) and samples[index][0] <= tick:
            instant, zone, channels = samples[index]
            latest[zone] = instant, channels
            index += 1
        for zone, bounds in zones.items():
            last = latest.get(zone)
            if last is None and tick - start < FRESHNESS:
                continue
            fresh = last is not None and tick - last[0] <= FRESHNESS
            for channel, (lower, upper), (quality, value) in zip(
                CHANNELS, bounds, last[1] if fresh else [("MISSING", None)] * 2
            ):
                sensor = f"SENSOR_{channel}_"
                environment = f"ZONE_{channel}_"
                condition = None
                if quality != "VALID":
                    condition = sensor + quality
                elif lower is not None and value < lower:
                    condition = environment + "LOW"
                elif upper is not None and value > upper:
                    condition = environment + "HIGH"

                for kind in (sensor+"MISSING", sensor+"INVALID", environment+"LOW", environment+"HIGH"):
                    key = zone, kind
                    if kind == condition:
                        active.setdefault(key, tick)
                    elif key in active and (kind.startswith(sensor) or quality == "VALID"):
                        reason = "RECOVERED" if quality == "VALID" else "SUPERSEDED"
                        intervals.append((zone, kind, active.pop(key), tick, reason))
        tick += STEP
    intervals.extend((zone, kind, opened, None, "OPEN_AT_REPLAY_END")
                     for (zone, kind), opened in active.items())
    return sorted(intervals, key=lambda row: (row[2], row[0], row[1]))


def main():
    inventory = ROOT / "cold_storage_demo.sqlite"
    with sqlite3.connect(inventory.as_uri() + "?mode=ro", uri=True) as db:
        zones = {code: ((tmin, tmax), (hmin, hmax)) for code, tmin, tmax, hmin, hmax
                 in db.execute("SELECT code,temp_min_c,temp_max_c,rh_min_pct,rh_max_pct FROM locations")}
    samples = load_samples(zones)
    out = csv.writer(sys.stdout, lineterminator="\n")
    out.writerow(("zone", "alert_type", "opened_at_utc", "closed_at_utc", "duration_seconds", "close_reason"))
    for zone, kind, start, end, reason in replay(zones, samples):
        duration = int((end-start).total_seconds()) if end else ""
        out.writerow((zone, kind, utc_text(start), utc_text(end), duration, reason))


if __name__ == "__main__":
    main()
