import { describe, expect, it } from 'vitest';
import { validateLimits } from './ThresholdsPage';

const ok = { temperatureLowerC: 0, temperatureUpperC: 4, humidityLowerPct: 55, humidityUpperPct: 80 };

describe('validateLimits', () => {
  it('accepts valid limits', () => expect(validateLimits(ok)).toBeUndefined());
  it('rejects missing values', () => expect(validateLimits({ ...ok, humidityLowerPct: NaN })).toMatch(/required/));
  it('rejects out-of-range values', () => {
    expect(validateLimits({ ...ok, temperatureLowerC: -41 })).toMatch(/-40 and 125/);
    expect(validateLimits({ ...ok, humidityUpperPct: 101 })).toMatch(/0 and 100/);
  });
  it('requires lower below upper', () => {
    expect(validateLimits({ ...ok, temperatureLowerC: 4 })).toMatch(/temperature/);
    expect(validateLimits({ ...ok, humidityLowerPct: 90 })).toMatch(/humidity/);
  });
});
