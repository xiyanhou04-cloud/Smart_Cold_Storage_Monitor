"""Run the accelerated zone-alert regression tests and print the replay intervals."""

import io
import sys
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path

from replay_zone_alerts import main, read_channel, replay

ROOT = Path(__file__).resolve().parent
START = datetime(2026, 11, 5, 17, tzinfo=timezone.utc)
NORMAL = (("VALID", 2), ("VALID", 65))
BOUNDS = {"TEST": ((0, 4), (50, 80))}


def at(seconds):
    return START + timedelta(seconds=seconds)


def sample(seconds, channels=NORMAL, zone="TEST"):
    return at(seconds), zone, channels


class AlertReplayTest(unittest.TestCase):
    def test_seven_zone_fixture_matches_expected_intervals(self):
        output = io.StringIO()
        with redirect_stdout(output):
            main()
        expected = (ROOT / "replay_alert_intervals.csv").read_text(encoding="ascii")
        self.assertMultiLineEqual(output.getvalue(), expected)

    def test_all_eight_alert_types_open_once_and_recover(self):
        scenarios = (
            (0, ("VALID", -1), "ZONE_TEMPERATURE_LOW"),
            (0, ("VALID", 5), "ZONE_TEMPERATURE_HIGH"),
            (1, ("VALID", 49), "ZONE_HUMIDITY_LOW"),
            (1, ("VALID", 81), "ZONE_HUMIDITY_HIGH"),
            (0, ("MISSING", None), "SENSOR_TEMPERATURE_MISSING"),
            (1, ("MISSING", None), "SENSOR_HUMIDITY_MISSING"),
            (0, ("INVALID", None), "SENSOR_TEMPERATURE_INVALID"),
            (1, ("INVALID", None), "SENSOR_HUMIDITY_INVALID"),
        )
        for channel, reading, kind in scenarios:
            with self.subTest(kind=kind):
                fault = list(NORMAL)
                fault[channel] = reading
                samples = [sample(0), sample(5, fault), sample(10, fault), sample(15)]
                expected = [("TEST", kind, at(5), at(15), "RECOVERED")]
                self.assertEqual(replay(BOUNDS, samples), expected)

    def test_threshold_endpoints_are_in_range(self):
        samples = [sample(0, (("VALID", 0), ("VALID", 50))),
                   sample(5, (("VALID", 4), ("VALID", 80)))]
        self.assertEqual(replay(BOUNDS, samples), [])

    def test_silent_device_reports_both_channels(self):
        zones = {**BOUNDS, "ONLINE": BOUNDS["TEST"]}
        samples = [sample(0)] + [sample(t, zone="ONLINE") for t in range(0, 26, 5)]
        expected = [
            ("TEST", "SENSOR_HUMIDITY_MISSING", at(20), None, "OPEN_AT_REPLAY_END"),
            ("TEST", "SENSOR_TEMPERATURE_MISSING", at(20), None, "OPEN_AT_REPLAY_END"),
        ]
        self.assertEqual(replay(zones, samples), expected)

    def test_missing_reading_does_not_close_environmental_alert(self):
        samples = [sample(0, (("VALID", 5), NORMAL[1])),
                   sample(5, (("MISSING", None), NORMAL[1])),
                   sample(10)]
        self.assertEqual(replay(BOUNDS, samples), [
            ("TEST", "ZONE_TEMPERATURE_HIGH", at(0), at(10), "RECOVERED"),
            ("TEST", "SENSOR_TEMPERATURE_MISSING", at(5), at(10), "RECOVERED"),
        ])

    def test_valid_sibling_channel_can_still_raise_an_alert(self):
        samples = [sample(0), sample(5, (("INVALID", None), ("VALID", 85)))]
        self.assertEqual(replay(BOUNDS, samples), [
            ("TEST", "SENSOR_TEMPERATURE_INVALID", at(5), None, "OPEN_AT_REPLAY_END"),
            ("TEST", "ZONE_HUMIDITY_HIGH", at(5), None, "OPEN_AT_REPLAY_END"),
        ])

    def test_channel_validation_distinguishes_missing_and_invalid(self):
        self.assertEqual(read_channel({}, "TEMPERATURE", None), ("MISSING", None))
        self.assertEqual(read_channel({"humidityPct": None}, "HUMIDITY", None), ("MISSING", None))
        for value in (float("inf"), float("nan"), "bad", True, 200):
            with self.subTest(value=value):
                self.assertEqual(read_channel({"temperatureC": value}, "TEMPERATURE", None),
                                 ("INVALID", None))
        self.assertEqual(read_channel({"humidityPct": 101}, "HUMIDITY", None), ("INVALID", None))
        row = {"temperatureC": 4.0032, "humidityPct": 69.99565}
        frame = bytes.fromhex("47 AF 00 9B A3 79")
        self.assertEqual(read_channel(row, "TEMPERATURE", frame), ("INVALID", None))
        self.assertEqual(read_channel(row, "HUMIDITY", frame)[0], "VALID")


if __name__ == "__main__":
    result = unittest.main(verbosity=2, exit=False).result
    if not result.wasSuccessful():
        sys.exit(1)
    print("\nSeven-zone alert intervals (UTC):")
    main()
