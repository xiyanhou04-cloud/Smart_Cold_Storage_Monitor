import type { Alert, DeviceReadings, DeviceThreshold, ReadingPoint, ReadingsByDevice } from '../api/types';

/**
 * Dashboard status rules (FR-03), as pure functions so they are unit tested without rendering anything.
 *
 * A zone's status is, in priority order: NO_DATA (no reading in the polled window), STALE (newest reading older than
 * `STALE_AFTER_MS`), EXCURSION (a value outside its limits, or an active alert), otherwise OK. Stale wins over
 * excursion because old values may not describe the zone any more. Range checks use the same rule as the backend's
 * `ThresholdAlertService`: a value equal to a limit is in range.
 */

/**
 * Devices report every 5 s (`app.simulator.interval`), so three missed readings mark a zone stale. Computed in the
 * browser until the backend adds `GET /api/readings/latest` with freshness; assumes browser and server clocks agree.
 */
export const STALE_AFTER_MS = 15_000;

export type Freshness = 'LIVE' | 'STALE' | 'NO_DATA';
export type RangeCheck = 'IN_RANGE' | 'BELOW' | 'ABOVE' | 'UNKNOWN';
export type DeviceStatus = 'OK' | 'EXCURSION' | 'STALE' | 'NO_DATA';

export interface DeviceSnapshot {
  deviceId: string;
  deviceName: string;
  locationCode: string | null;
  temperature: ReadingPoint | undefined;
  humidity: ReadingPoint | undefined;
  temperatureCheck: RangeCheck;
  humidityCheck: RangeCheck;
  freshness: Freshness;
  /** Age of the newest reading in ms, or undefined without data. */
  ageMs: number | undefined;
  activeAlerts: number;
  unacknowledgedAlerts: number;
  threshold: DeviceThreshold | undefined;
  status: DeviceStatus;
}

export function latest(points: ReadingPoint[] | undefined): ReadingPoint | undefined {
  return points && points.length > 0 ? points[points.length - 1] : undefined;
}

export function freshness(timestamp: string | undefined, now: number, staleAfterMs = STALE_AFTER_MS): Freshness {
  if (!timestamp) return 'NO_DATA';
  return now - Date.parse(timestamp) > staleAfterMs ? 'STALE' : 'LIVE';
}

/** A value equal to a limit is in range, matching `ThresholdAlertService`. UNKNOWN when there is no value or limit. */
export function checkRange(
  value: number | undefined,
  lower: number | null | undefined,
  upper: number | null | undefined,
): RangeCheck {
  if (value === undefined || lower == null || upper == null) return 'UNKNOWN';
  if (value < lower) return 'BELOW';
  if (value > upper) return 'ABOVE';
  return 'IN_RANGE';
}

/** Priority: no data, then stale (values cannot be trusted), then excursion, then OK. */
export function deriveStatus(
  fresh: Freshness,
  temperature: RangeCheck,
  humidity: RangeCheck,
  activeAlerts: number,
): DeviceStatus {
  if (fresh === 'NO_DATA') return 'NO_DATA';
  if (fresh === 'STALE') return 'STALE';
  const outOfRange = (c: RangeCheck) => c === 'ABOVE' || c === 'BELOW';
  if (outOfRange(temperature) || outOfRange(humidity) || activeAlerts > 0) return 'EXCURSION';
  return 'OK';
}

function newestTimestamp(readings: DeviceReadings | undefined): string | undefined {
  const t = latest(readings?.temperatureC)?.timestamp;
  const h = latest(readings?.humidityPct)?.timestamp;
  if (!t) return h;
  if (!h) return t;
  return Date.parse(t) >= Date.parse(h) ? t : h;
}

/** Joins readings, thresholds, and active alerts into one row per device, ordered by device id. */
export function buildSnapshots(
  readings: ReadingsByDevice | undefined,
  thresholds: DeviceThreshold[] | undefined,
  activeAlerts: Alert[] | undefined,
  now: number,
): DeviceSnapshot[] {
  const byId = new Map((thresholds ?? []).map((t) => [t.deviceId, t]));
  const ids = new Set<string>([...Object.keys(readings ?? {}), ...byId.keys()]);

  return [...ids].sort().map((deviceId) => {
    const device = readings?.[deviceId];
    const threshold = byId.get(deviceId);
    const temperature = latest(device?.temperatureC);
    const humidity = latest(device?.humidityPct);
    const newest = newestTimestamp(device);
    const fresh = freshness(newest, now);
    const alerts = (activeAlerts ?? []).filter((a) => a.deviceId === deviceId && a.state === 'ACTIVE');
    const temperatureCheck = checkRange(temperature?.value, threshold?.temperatureLowerC, threshold?.temperatureUpperC);
    const humidityCheck = checkRange(humidity?.value, threshold?.humidityLowerPct, threshold?.humidityUpperPct);
    return {
      deviceId,
      deviceName: threshold?.deviceName ?? deviceId,
      locationCode: threshold?.locationCode ?? null,
      temperature,
      humidity,
      temperatureCheck,
      humidityCheck,
      freshness: fresh,
      ageMs: newest ? Math.max(0, now - Date.parse(newest)) : undefined,
      activeAlerts: alerts.length,
      unacknowledgedAlerts: alerts.filter((a) => !a.acknowledgment).length,
      threshold,
      status: deriveStatus(fresh, temperatureCheck, humidityCheck, alerts.length),
    };
  });
}

export interface ChartPoint {
  time: number;
  temperature?: number;
  humidity?: number;
}

/** Merges the two metric series on their timestamps for one chart, oldest first. */
export function mergeSeries(readings: DeviceReadings | undefined): ChartPoint[] {
  const points = new Map<number, ChartPoint>();
  for (const p of readings?.temperatureC ?? []) {
    const time = Date.parse(p.timestamp);
    points.set(time, { ...points.get(time), time, temperature: p.value });
  }
  for (const p of readings?.humidityPct ?? []) {
    const time = Date.parse(p.timestamp);
    points.set(time, { ...points.get(time), time, humidity: p.value });
  }
  return [...points.values()].sort((a, b) => a.time - b.time);
}

export function summarize(snapshots: DeviceSnapshot[]): Record<DeviceStatus, number> {
  const counts: Record<DeviceStatus, number> = { OK: 0, EXCURSION: 0, STALE: 0, NO_DATA: 0 };
  snapshots.forEach((s) => counts[s.status]++);
  return counts;
}
