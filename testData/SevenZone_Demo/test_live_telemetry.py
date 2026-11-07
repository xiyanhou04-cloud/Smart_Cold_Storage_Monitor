"""Send seven live HTTP streams and verify persisted zone alerts."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import getpass
import hashlib
import hmac
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener
from uuid import uuid4

ZONES = {
    "RAW-MEAT": (1, 2.0, 65.0),
    "VEG-COLD": (2, 1.0, 96.0),
    "VEG-COOL": (3, 11.0, 92.0),
    "EGGS": (4, 2.0, 65.0),
    "FROZEN": (5, -22.0, 60.0),
    "FRUIT-COLD-BERRY": (6, 0.0, 92.0),
    "FRUIT-TROPICAL": (7, 13.5, 92.0),
}
FAULTS = {
    "RAW-MEAT": (6.0, 65.0),
    "VEG-COLD": (-1.0, 99.0),
    "VEG-COOL": (200.0, 80.0),
    "EGGS": (None, None),
    "FROZEN": (-22.0, 101.0),
}
EXPECTED = {
    ("RAW-MEAT", "ZONE_TEMPERATURE_HIGH"),
    ("VEG-COLD", "ZONE_TEMPERATURE_LOW"),
    ("VEG-COLD", "ZONE_HUMIDITY_HIGH"),
    ("VEG-COOL", "SENSOR_TEMPERATURE_INVALID"),
    ("VEG-COOL", "ZONE_HUMIDITY_LOW"),
    ("EGGS", "SENSOR_TEMPERATURE_MISSING"),
    ("EGGS", "SENSOR_HUMIDITY_MISSING"),
    ("FROZEN", "SENSOR_HUMIDITY_INVALID"),
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class Client:
    def __init__(self, base, secret):
        self.base = base.rstrip("/")
        self.secret = secret.encode()
        self.browser = build_opener(HTTPCookieProcessor(CookieJar()))
        self.machine = build_opener()

    def request(self, path, *, data=None, headers=None, status=200, machine=False):
        opener = self.machine if machine else self.browser
        try:
            with opener.open(Request(self.base + path, data=data, headers=headers or {}), timeout=5) as response:
                actual, body = response.status, response.read()
        except HTTPError as exc:
            actual, body = exc.code, exc.read()
        require(actual == status, f"{path}: expected HTTP {status}, got {actual}: {body[:500]!r}")
        return json.loads(body) if body else None

    def login(self, username, password):
        csrf = self.request("/api/auth/csrf")
        self.request("/api/auth/login", data=urlencode({"username": username, "password": password}).encode(),
                     headers={csrf["headerName"]: csrf["token"],
                              "Content-Type": "application/x-www-form-urlencoded"})

    def send(self, device, sample, status=201, token=None):
        if token is None:
            token = hmac.new(self.secret, device.encode(), hashlib.sha256).hexdigest()
        return self.request("/api/telemetry/readings", data=json.dumps(sample).encode(), status=status, machine=True,
                            headers={"X-Device-Id": device, "X-Device-Token": token,
                                     "Content-Type": "application/json"})

    def events(self, cursor):
        return self.request(f"/api/monitoring/alert-events?afterId={cursor}&limit=500")

    def latest_event_id(self):
        cursor = 0
        while batch := self.events(cursor):
            cursor = batch[-1]["id"]
        return cursor


def channel(value):
    return {"value": value, "state": "MISSING" if value is None else "PRESENT"}


def payload(boot, seq, measured, temperature, humidity, frame=None):
    return {"bootId": boot, "sequence": seq, "measuredAt": measured, "synthetic": True,
            "temperature": channel(temperature), "humidity": channel(humidity), "rawFrameHex": frame}


def sleep_until(deadline):
    time.sleep(max(0, deadline - time.monotonic()))


def show_alert_status(client, tick):
    alerts = client.request("/api/monitoring/alerts?state=OPEN&limit=500")
    print(f"Tick {tick}: 7 readings accepted | Latest backend alert status", flush=True)
    if not alerts:
        print("  No active zone alerts.", flush=True)
        return
    for alert in sorted(alerts, key=lambda item: (item["location"], item["type"])):
        details = ""
        if alert["observedValue"] is not None and alert["threshold"] is not None:
            unit = "C" if "TEMPERATURE" in alert["type"] else "%RH"
            details = f" | reading {alert['observedValue']:g} {unit}, limit {alert['threshold']:g} {unit}"
        print(f"  {alert['location']}: {alert['type']}{details}", flush=True)


def smoke(client):
    boot = "smoke-" + uuid4().hex
    start = time.monotonic()
    events = []
    cursor = None
    for seq in range(8):
        sleep_until(start + seq * 5)
        measured = utc_now()
        if seq == 2:
            open_alerts = client.request("/api/monitoring/alerts?state=OPEN&limit=500")
            require(not EXPECTED.intersection((a["location"], a["type"]) for a in open_alerts),
                    "Baseline alerts did not clear before fault injection.")
            cursor = client.latest_event_id()
        for zone, (number, temperature, humidity) in ZONES.items():
            if 2 <= seq <= 4:
                temperature, humidity = FAULTS.get(zone, (temperature, humidity))
            item = payload(boot, seq, measured, temperature, humidity)
            device = f"ESP32-SIM-{number:03}"
            if seq == 0 and number == 1:
                client.send(device, item, status=401, token="0" * 64)
            client.send(device, item)
            if seq == 0 and number == 1:
                client.send(device, item, status=200)
                conflict = dict(item, temperature=channel(3.5))
                client.send(device, conflict, status=409)
        if cursor is not None:
            batch = client.events(cursor)
            events.extend(batch)
            if batch:
                cursor = batch[-1]["id"]
        show_alert_status(client, seq)
        if seq == 4:
            opened = {(e["location"], e["alertType"]) for e in events if e["type"] == "OPENED"}
            require(EXPECTED <= opened, f"Alerts did not open while faults were active: {EXPECTED - opened}")

    samples = client.request(f"/api/telemetry/samples?bootId={boot}&limit=500")
    require(len(samples) == 56, f"Expected 56 persisted samples, got {len(samples)}")
    require(Counter(s["location"] for s in samples) == dict.fromkeys(ZONES, 8),
            "Expected eight persisted samples per zone.")
    faults = {(s["location"], s["sequence"]): (s["temperatureState"], s["humidityState"]) for s in samples}
    require(faults[("VEG-COOL", 2)] == ("INVALID", "VALID"), "Temperature fault was not isolated.")
    require(faults[("FROZEN", 2)] == ("VALID", "INVALID"), "Humidity fault was not isolated.")
    require(faults[("EGGS", 2)] == ("MISSING", "MISSING"), "Missing channels were not persisted.")

    by_key = defaultdict(list)
    for event in events:
        by_key[event["location"], event["alertType"]].append(event)
    require(set(by_key) == EXPECTED, f"Unexpected alert transitions: {set(by_key) ^ EXPECTED}")
    for key in sorted(EXPECTED):
        pair = by_key[key]
        require([e["type"] for e in pair] == ["OPENED", "CLOSED"],
                f"{key}: expected exactly one opening and one closure.")
        require(pair[0]["alertId"] == pair[1]["alertId"] and pair[0]["occurredAt"] < pair[1]["occurredAt"],
                f"{key}: inconsistent incident identity or timestamps.")
        print(f"{key[0]:16} {key[1]:28} {pair[0]['occurredAt']} -> {pair[1]['occurredAt']}")
    print("PASS: 56 persisted readings and all eight alert types opened and recovered over HTTP.")


def stream(client):
    source = Path(__file__).with_name("monitoring_timestamped.jsonl")
    with source.open(encoding="utf-8") as stream_file:
        rows = [json.loads(line) for line in stream_file]
    boot = "stream-" + uuid4().hex
    start = time.monotonic()
    measured = ""
    previous = None
    for row in rows:
        seq = row["sequence_number"]
        if seq != previous:
            sleep_until(start + seq * 5)
            measured = utc_now()
            previous = seq
            print(f"Streaming tick {seq}/360", flush=True)
        client.send(row["deviceId"], payload(boot, seq, measured, row["temperatureC"],
                                             row["humidityPct"], row["frame_hex"]))
    print(f"Completed {len(rows)} HTTP submissions for boot ID {boot}.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--mode", choices=("smoke", "stream"), default="smoke")
    parser.add_argument("--username", default="admin")
    args = parser.parse_args()
    address = urlsplit(args.base)
    require(address.scheme in ("http", "https") and address.hostname in ("127.0.0.1", "localhost", "::1"),
            "The demo client expects a loopback server.")
    secret = os.environ.get("APP_TELEMETRY_DEVICE_SECRET")
    require(secret and len(secret) >= 16, "Set APP_TELEMETRY_DEVICE_SECRET to the server's demo secret.")
    client = Client(args.base, secret)
    if args.mode == "smoke":
        password = os.environ.get("TELEMETRY_TEST_PASSWORD") or getpass.getpass("Backend password: ")
        client.login(args.username, password)
        smoke(client)
    else:
        stream(client)


if __name__ == "__main__":
    main()
