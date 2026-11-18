import { describe, expect, it } from 'vitest';
import type { Alert, DeviceThreshold } from '../api/types';
import {
  buildSnapshots,
  checkRange,
  deriveStatus,
  freshness,
  latest,
  mergeSeries,
  STALE_AFTER_MS,
  summarize,
} from './status';

const NOW = Date.parse('2026-11-17T10:00:00Z');
const iso = (msAgo: number) => new Date(NOW - msAgo).toISOString();

function threshold(deviceId: string, overrides: Partial<DeviceThreshold> = {}): DeviceThreshold {
  return {
    deviceId,
    deviceName: deviceId,
    locationCode: 'RAW-MEAT',
    enabled: true,
    sessionId: 1,
    thresholdId: 1,
    temperatureLowerC: 0,
    temperatureUpperC: 4,
    humidityLowerPct: 55,
    humidityUpperPct: 80,
    effectiveFrom: iso(60_000),
    source: 'DEFAULT',
    changedBy: null,
    pendingRequest: null,
    ...overrides,
  };
}

describe('checkRange', () => {
  it('treats values equal to a limit as in range', () => {
    expect(checkRange(0, 0, 4)).toBe('IN_RANGE');
    expect(checkRange(4, 0, 4)).toBe('IN_RANGE');
  });
  it('detects values below and above', () => {
    expect(checkRange(-0.1, 0, 4)).toBe('BELOW');
    expect(checkRange(4.01, 0, 4)).toBe('ABOVE');
  });
  it('is UNKNOWN without a value or limits', () => {
    expect(checkRange(undefined, 0, 4)).toBe('UNKNOWN');
    expect(checkRange(2, null, 4)).toBe('UNKNOWN');
  });
});

describe('freshness', () => {
  it('is LIVE within the stale window and STALE after it', () => {
    expect(freshness(iso(STALE_AFTER_MS), NOW)).toBe('LIVE');
    expect(freshness(iso(STALE_AFTER_MS + 1), NOW)).toBe('STALE');
  });
  it('is NO_DATA without a timestamp', () => {
    expect(freshness(undefined, NOW)).toBe('NO_DATA');
  });
});

describe('deriveStatus', () => {
  it('ranks no data and stale above excursions', () => {
    expect(deriveStatus('NO_DATA', 'ABOVE', 'IN_RANGE', 1)).toBe('NO_DATA');
    expect(deriveStatus('STALE', 'ABOVE', 'IN_RANGE', 1)).toBe('STALE');
  });
  it('reports an excursion for an out-of-range value or an active alert', () => {
    expect(deriveStatus('LIVE', 'IN_RANGE', 'BELOW', 0)).toBe('EXCURSION');
    expect(deriveStatus('LIVE', 'IN_RANGE', 'IN_RANGE', 1)).toBe('EXCURSION');
    expect(deriveStatus('LIVE', 'IN_RANGE', 'IN_RANGE', 0)).toBe('OK');
  });
});

describe('buildSnapshots', () => {
  const readings = {
    B: {
      temperatureC: [
        { timestamp: iso(10_000), value: 3 },
        { timestamp: iso(5_000), value: 4.6 },
      ],
      humidityPct: [{ timestamp: iso(5_000), value: 70 }],
    },
    A: { temperatureC: [{ timestamp: iso(60_000), value: 2 }], humidityPct: [] },
  };
  const alert = { id: 1, deviceId: 'B', state: 'ACTIVE', acknowledgment: null } as Alert;

  it('joins readings, thresholds, and alerts per device in id order', () => {
    const snaps = buildSnapshots(readings, [threshold('A'), threshold('B'), threshold('C')], [alert], NOW);
    expect(snaps.map((s) => s.deviceId)).toEqual(['A', 'B', 'C']);

    const b = snaps[1];
    expect(b.temperature?.value).toBe(4.6);
    expect(b.temperatureCheck).toBe('ABOVE');
    expect(b.humidityCheck).toBe('IN_RANGE');
    expect(b.activeAlerts).toBe(1);
    expect(b.unacknowledgedAlerts).toBe(1);
    expect(b.ageMs).toBe(5_000);
    expect(b.status).toBe('EXCURSION');

    expect(snaps[0].status).toBe('STALE');
    expect(snaps[2].status).toBe('NO_DATA');
    expect(summarize(snaps)).toEqual({ OK: 0, EXCURSION: 1, STALE: 1, NO_DATA: 1 });
  });

  it('falls back to the device id when thresholds have not loaded', () => {
    const [a] = buildSnapshots(readings, undefined, undefined, NOW);
    expect(a.deviceName).toBe('A');
    expect(a.temperatureCheck).toBe('UNKNOWN');
  });
});

describe('series helpers', () => {
  it('latest returns the last point', () => {
    expect(
      latest([
        { timestamp: iso(2), value: 1 },
        { timestamp: iso(1), value: 2 },
      ])?.value,
    ).toBe(2);
    expect(latest([])).toBeUndefined();
  });

  it('mergeSeries joins both metrics on timestamp, oldest first', () => {
    const merged = mergeSeries({
      temperatureC: [
        { timestamp: iso(0), value: 2 },
        { timestamp: iso(5000), value: 1 },
      ],
      humidityPct: [{ timestamp: iso(5000), value: 60 }],
    });
    expect(merged).toEqual([
      { time: NOW - 5000, temperature: 1, humidity: 60 },
      { time: NOW, temperature: 2 },
    ]);
  });
});
