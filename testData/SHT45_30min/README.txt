SHT45 30-minute synthetic replay / CS5010

361 frames at t=0,5,...,1800 seconds (both endpoints included).
Each frame: T_MSB T_LSB T_CRC RH_MSB RH_LSB RH_CRC.
CRC-8: polynomial 0x31, init 0xFF, no reflection, final XOR 0.
.bin has no header or timestamps: frame i is bytes [6*i,6*i+6), elapsed=5*i.
All frames have valid CRC; no communication faults are injected.
Source: https://sensirion.com/resource/datasheet/sht4x
Synthetic scenarios, not measured data, not a calibrated sensor noise model.
No risk labels are provided. One session is insufficient for ML evaluation.

Scenario anchors (linear interpolation plus small seeded fluctuations):
0-8 min: approximately 4 C / 70 %RH, normal.
8-10 min: rise toward 9 C / 79 %RH, simulated door opening.
10-12 min: warm plateau.
12-16 min: recovery toward 4 C / 72 %RH.
16-20 min: normal, humidity returns toward 70 %RH.
20-24 min: rise toward 12 C / 82 %RH, simulated cooling failure.
24-26 min: warm plateau.
26-30 min: recovery toward 4 C / 72 %RH.
Illustrative control-test thresholds: 2 to 8 C, not safety recommendations.

ESP32 / Arduino use:
1. Open SHT45_30min.ino in Arduino IDE. If asked, allow it to create
   a sketch folder named SHT45_30min; keep sht45_sim.h beside the sketch.
2. Select your ESP32 board, build and upload. Serial monitor: 115200 baud.
3. Replay starts on boot, emits one decoded JSON reading every 5 seconds,
   and stops after the final sample at 30 minutes. Reset to replay again.
4. For your own firmware, include sht45_sim.h and call
   readSimulatedSht45(index, buffer) instead of the Wire sensor-read operation.
   This replaces the acquisition layer; it does NOT emulate an I2C peripheral.
5. Alternatively upload .bin to your chosen ESP32 filesystem and read 6 bytes
   at a time; filesystem configuration/upload is not included here.

The JSONL file is for inspecting frames and decoded values, not the final
backend API payload (deviceId, sessionId and absolute UTC time must be added).
Validation: all frames round-trip decoded and CRC checked by generator.
ESP32 sketch supplied for integration; not compiled or tested on hardware.


See TIMESTAMPS_AND_CONTINUOUS_LOGGING.md for the independent sensor stream and clock contract. The timestamped JSONL is in this directory. The supplied sketch is a finite replay, not a 24/7 logger.

The combined monitoring_timestamped.jsonl includes all five zones at every
5-second UTC tick from 17:00:00Z through 17:30:00Z on 2026-11-05. Each zone
has its own device ID and 361 frames (1,805 total). location_code is descriptive
metadata; application-monitoring-demo.yaml defines the actual mapping.
The standalone binary, header and Arduino sketch replay the original raw meat
device only. The backend reads the combined JSONL file.
