"""Stream a complete recorded sensor run through the backend alert evaluator."""
import argparse
import getpass
import json
import math
import os
from urllib.parse import urlencode, urlsplit

from test_live_telemetry import Client, require


def speed_input(value):
    while value is None:
        try:
            value = float(input("Replay speed multiplier (0.1 to 1000, e.g. 10): ").strip())
        except ValueError:
            print("Enter a numeric multiplier.")
            continue
        if not math.isfinite(value) or not 0.1 <= value <= 1000:
            print("Enter a speed from 0.1 to 1000.")
            value = None
    require(math.isfinite(value) and 0.1 <= value <= 1000, "Enter a speed from 0.1 to 1000.")
    return value


def show_channel(observation, unit):
    if observation is None:
        return "NO FRESH SAMPLE"
    if observation["state"] != "VALID":
        return observation["state"]
    return f"{observation['value']:.2f}{unit}"


def show_tick(tick):
    active = tick["activeAlerts"]
    print(f"[{tick['measuredAt']}] Tick {tick['tick']} | "
          f"{tick['readingsThisTick']} new / {tick['processedReadings']} total | "
          f"{len(active)} active zone alert(s)")
    flagged = {}
    for alert in active:
        label = alert["alertType"]
        if alert["evidence"] == "UNKNOWN":
            label += " (evidence unavailable)"
        flagged.setdefault(alert["location"], []).append(label)
    for zone in tick["zones"]:
        temperature = show_channel(zone["temperature"], " C")
        humidity = show_channel(zone["humidity"], "%RH")
        state = ", ".join(flagged.get(zone["location"], [])) or "No active zone alerts"
        print(f"  {zone['location']}: T={temperature} RH={humidity} | {state}")
    for event in tick["transitions"]:
        reason = f" ({event['closeReason']})" if event.get("closeReason") else ""
        print(f"  {event['event']} #{event['alertId']} {event['location']} "
              f"{event['alertType']} at {event['occurredAt']}{reason}")
    print(flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--speed", type=float, help="Provide a multiplier for unattended runs.")
    args = parser.parse_args()
    address = urlsplit(args.base)
    require(address.scheme in ("http", "https") and address.hostname in ("127.0.0.1", "localhost", "::1"),
            "The replay client expects a loopback server.")
    speed = speed_input(args.speed)
    client = Client(args.base, "")
    password = os.environ.get("TELEMETRY_TEST_PASSWORD") or getpass.getpass("Backend password: ")
    client.login(args.username, password)
    query = urlencode({"speed": speed})
    print(f"Starting recorded backend replay at {speed:g}x.", flush=True)
    processed = 0
    ticks = 0
    complete = None
    with client.browser.open(f"{client.base}/api/monitoring/recorded-alert-replay?{query}", timeout=120) as response:
        require(response.headers.get_content_type() == "application/x-ndjson",
                "The server did not return the recorded replay stream.")
        for line in response:
            message = json.loads(line)
            if message["type"] == "COMPLETE":
                complete = message
                break
            require(message["type"] == "TICK" and message["tick"] == ticks + 1,
                    "The server returned an invalid replay sequence.")
            ticks += 1
            processed += message["readingsThisTick"]
            require(processed == message["processedReadings"], "Inconsistent reading count.")
            show_tick(message)
    require(complete is not None, "The backend stopped before completing the recording.")
    require(complete["ticks"] == ticks and complete["processedReadings"] == processed,
            "The backend completion count does not match the streamed output.")
    print(f"COMPLETE: {processed} readings across {ticks} ticks. "
          f"{complete['firstAt']} -> {complete['lastAt']}")


if __name__ == "__main__":
    main()
