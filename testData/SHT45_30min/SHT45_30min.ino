#include <Arduino.h>
#include "sht45_sim.h"

uint8_t crc8(const uint8_t *p, size_t n) {
  uint8_t c = 0xFF;
  while (n--) {
    c ^= *p++;
    for (int k=0; k<8; ++k) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x31) : (uint8_t)(c << 1);
  }
  return c;
}
size_t sampleIndex = 0;
uint32_t replayStart;
void setup() {
  Serial.begin(115200);
  replayStart = millis();
}
void loop() {
  if (sampleIndex >= SHT45_FRAME_COUNT) return;
  if ((uint32_t)(millis()-replayStart) < sampleIndex*SHT45_INTERVAL_MS) return;
  uint8_t b[6];
  if (!readSimulatedSht45(sampleIndex,b)) return;
  if (crc8(b,2)!=b[2] || crc8(b+3,2)!=b[5]) {
    Serial.println("CRC_ERROR");
  } else {
    uint16_t rt = ((uint16_t)b[0]<<8)|b[1];
    uint16_t rh = ((uint16_t)b[3]<<8)|b[4];
    float t = -45.0f+175.0f*rt/65535.0f;
    float h = constrain(-6.0f+125.0f*rh/65535.0f,0.0f,100.0f);
    Serial.printf("{\"elapsed_seconds\":%lu,\"temperatureC\":%.4f,\"humidityPct\":%.4f}\n",
      (unsigned long)(sampleIndex*5),t,h);
  }
  ++sampleIndex;
}
