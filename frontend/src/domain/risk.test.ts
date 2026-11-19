import { describe, expect, it } from 'vitest';
import type { LatestReading, RiskSummary } from '../api/types';
import { countHistoryGaps, gapImpact, hasHistoryGap, integrityOf, riskView, sourceLabel } from './risk';

const base: RiskSummary = {
  riskLevel: 'LOW',
  source: 'MODEL',
  modelOrRuleVersion: 'random-forest-v1',
  predictedAt: '2026-11-19T10:00:00.100Z',
  qualityFlag: 'OK',
  historicalExposureIncomplete: false,
  currentGapReason: 'NONE',
  lastGapReason: 'NONE',
};

function latest(prediction: Partial<RiskSummary> | null, stale = false): LatestReading {
  return {
    reading: {
      id: 1,
      deviceId: 'ESP32-SIM-001',
      sessionId: 1,
      measuredAt: '2026-11-19T10:00:00Z',
      receivedAt: '2026-11-19T10:00:00.050Z',
      temperatureC: 3.2,
      humidityPct: 70,
      thresholdId: 1,
    },
    stale,
    prediction: prediction === null ? null : { ...base, ...prediction },
  };
}

describe('integrityOf', () => {
  it('is complete without any gap', () => {
    expect(integrityOf(base)).toEqual({ integrity: 'COMPLETE' });
  });

  it('flags a gap that ends at the current reading, even before the history flag is set', () => {
    expect(integrityOf({ ...base, currentGapReason: 'ORDERLY_MONITORING_INTERRUPTION' })).toEqual({
      integrity: 'CURRENT_GAP',
      gapReason: 'ORDERLY_MONITORING_INTERRUPTION',
    });
  });

  it('flags an earlier gap with its last reason', () => {
    expect(
      integrityOf({
        ...base,
        historicalExposureIncomplete: true,
        lastGapReason: 'UNCONFIRMED_MONITORING_INTERRUPTION',
      }),
    ).toEqual({ integrity: 'EARLIER_GAP', gapReason: 'UNCONFIRMED_MONITORING_INTERRUPTION' });
  });

  it('falls back to an unattributed reason when the earlier gap has none', () => {
    expect(integrityOf({ ...base, historicalExposureIncomplete: true, lastGapReason: null })).toEqual({
      integrity: 'EARLIER_GAP',
      gapReason: 'LEGACY_UNATTRIBUTED',
    });
  });

  it('reports predictions without gap fields as not recorded, not as complete', () => {
    expect(
      integrityOf({ ...base, historicalExposureIncomplete: null, currentGapReason: null, lastGapReason: null }),
    ).toEqual({ integrity: 'NOT_RECORDED' });
  });
});

describe('riskView', () => {
  it('distinguishes no reading, a pending prediction, and a prediction', () => {
    expect(riskView(undefined).state).toBe('NONE');
    expect(riskView(latest(null)).state).toBe('PENDING');
    const view = riskView(latest({ riskLevel: 'HIGH' }));
    expect(view).toMatchObject({ state: 'PREDICTED', level: 'HIGH', source: 'MODEL', integrity: 'COMPLETE' });
    expect(hasHistoryGap(view)).toBe(false);
    expect(gapImpact(view)).toBeNull();
  });

  it('warns that an earlier gap may understate a model rating', () => {
    const view = riskView(latest({ historicalExposureIncomplete: true, lastGapReason: 'RUNNING_DATA_GAP' }));
    expect(hasHistoryGap(view)).toBe(true);
    expect(gapImpact(view)).toMatch(/may be lower than the real risk/);
  });

  it('explains that the rules rated a reading straight after a gap', () => {
    const view = riskView(
      latest({ source: 'RULES', qualityFlag: 'INCOMPLETE_HISTORY', currentGapReason: 'RUNNING_DATA_GAP' }),
    );
    expect(sourceLabel(view)).toBe('Rules fallback');
    expect(gapImpact(view)).toMatch(/ML model was not used/);
    expect(view.modelUnavailable).toBeUndefined();
  });

  it('marks a model outage separately from a gap', () => {
    const view = riskView(latest({ source: 'RULES', qualityFlag: 'MODEL_MISSING' }));
    expect(view.modelUnavailable).toBe('MODEL_MISSING');
    expect(hasHistoryGap(view)).toBe(false);
  });

  it('carries the backend stale flag and counts gaps', () => {
    const views = [
      riskView(latest({}, true)),
      riskView(latest({ currentGapReason: 'UNOBSERVED_PREFIX' })),
      riskView(latest({ historicalExposureIncomplete: true })),
    ];
    expect(views[0].stale).toBe(true);
    expect(countHistoryGaps(views)).toBe(2);
  });
});
