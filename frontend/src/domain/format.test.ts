import { describe, expect, it } from 'vitest';
import {
  formatAge,
  formatDuration,
  formatHumidity,
  formatRange,
  formatTemperature,
  humanize,
  localInputToIso,
} from './format';

describe('format', () => {
  it('formats values with units and placeholders', () => {
    expect(formatTemperature(4.04)).toBe('4.0 °C');
    expect(formatHumidity(69.99)).toBe('70.0 %RH');
    expect(formatTemperature(null)).toBe('—');
    expect(formatRange(0, 4, '°C')).toBe('0.0 to 4.0 °C');
    expect(formatRange(null, 4, '°C')).toBe('—');
  });

  it('formats ages and durations', () => {
    expect(formatAge(undefined)).toBe('no data');
    expect(formatAge(4_000)).toBe('4 s ago');
    expect(formatAge(180_000)).toBe('3 min ago');
    expect(formatDuration('2026-11-17T10:00:00Z', '2026-11-17T10:02:05Z')).toBe('2 min 5 s');
    expect(formatDuration('2026-11-17T10:00:00Z', '2026-11-17T12:30:00Z')).toBe('2 h 30 min');
  });

  it('humanizes enum codes', () => {
    expect(humanize('THRESHOLD_CHANGE_REQUESTED')).toBe('Threshold change requested');
    expect(humanize(null)).toBe('—');
  });

  it('converts datetime-local input to a UTC instant', () => {
    expect(localInputToIso('')).toBeUndefined();
    expect(localInputToIso('2026-11-17T10:00')).toBe(new Date('2026-11-17T10:00').toISOString());
  });
});
