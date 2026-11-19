import type { GapReason, LatestReading, QualityFlag, RiskLevel, RiskSummary } from '../api/types';

/**
 * How the dashboard presents a zone's latest risk prediction (FR-05), as pure functions so the rules are unit tested.
 *
 * The backend counts time above the temperature limit only across intervals of at most 30 s. A longer gap (missed
 * readings, a server restart) leaves that time uncounted, so the user has to know when a rating rests on incomplete
 * history:
 *
 * - CURRENT_GAP: the gap ends at this very reading. The model was skipped and the rules rated it (`currentGapReason`).
 * - EARLIER_GAP: an earlier gap in this storage session (`historicalExposureIncomplete`). The model still ran, but the
 *   exposure it saw is too low, so the rating may be lower than the real risk.
 * - NOT_RECORDED: a prediction stored before the gap fields existed (all null); completeness is unknown.
 * - COMPLETE: no gap in this session.
 */

export type HistoryIntegrity = 'COMPLETE' | 'CURRENT_GAP' | 'EARLIER_GAP' | 'NOT_RECORDED';

export interface RiskView {
  /** PENDING: a reading exists but is not scored yet. NONE: the device has no reading. */
  state: 'PREDICTED' | 'PENDING' | 'NONE';
  level?: RiskLevel;
  source?: RiskSummary['source'];
  version?: string;
  predictedAt?: string;
  integrity: HistoryIntegrity;
  gapReason?: GapReason;
  /** Set when the model could not be used for a reason other than a gap. */
  modelUnavailable?: QualityFlag;
  /** The backend's own 30 s staleness flag for the scored reading. */
  stale: boolean;
}

const MODEL_UNAVAILABLE: QualityFlag[] = ['MODEL_MISSING', 'MODEL_INCOMPATIBLE', 'MODEL_ERROR', 'UNKNOWN_PRODUCT'];

export function integrityOf(p: RiskSummary): { integrity: HistoryIntegrity; gapReason?: GapReason } {
  if (p.currentGapReason && p.currentGapReason !== 'NONE') {
    return { integrity: 'CURRENT_GAP', gapReason: p.currentGapReason };
  }
  if (p.historicalExposureIncomplete) {
    const last = p.lastGapReason && p.lastGapReason !== 'NONE' ? p.lastGapReason : 'LEGACY_UNATTRIBUTED';
    return { integrity: 'EARLIER_GAP', gapReason: last };
  }
  if (p.historicalExposureIncomplete === null && p.currentGapReason === null) return { integrity: 'NOT_RECORDED' };
  return { integrity: 'COMPLETE' };
}

export function riskView(latest: LatestReading | undefined): RiskView {
  if (!latest) return { state: 'NONE', integrity: 'COMPLETE', stale: false };
  const p = latest.prediction;
  if (!p) return { state: 'PENDING', integrity: 'COMPLETE', stale: latest.stale };
  return {
    state: 'PREDICTED',
    level: p.riskLevel,
    source: p.source,
    version: p.modelOrRuleVersion,
    predictedAt: p.predictedAt,
    ...integrityOf(p),
    modelUnavailable: MODEL_UNAVAILABLE.includes(p.qualityFlag) ? p.qualityFlag : undefined,
    stale: latest.stale,
  };
}

/** True when the rating may not reflect the zone's full history, so the UI must warn. */
export function hasHistoryGap(view: RiskView): boolean {
  return view.integrity === 'CURRENT_GAP' || view.integrity === 'EARLIER_GAP';
}

export function sourceLabel(view: RiskView): string {
  if (view.source === 'MODEL') return 'ML model';
  if (view.source === 'RULES') return 'Rules fallback';
  return '—';
}

/** What caused the gap, in operator terms. */
export const GAP_REASON_TEXT: Record<GapReason, string> = {
  NONE: 'No gap.',
  RUNNING_DATA_GAP: 'Readings were missing for more than 30 s while monitoring was running.',
  ORDERLY_MONITORING_INTERRUPTION: 'Monitoring was stopped, for example by a server restart, and resumed later.',
  UNCONFIRMED_MONITORING_INTERRUPTION:
    'Monitoring may have been interrupted, but the server could not confirm when it stopped and restarted.',
  MONITORING_INTERRUPTION_WITH_RUNNING_GAP:
    'Monitoring was stopped and restarted, and readings were also missing around the restart.',
  UNOBSERVED_PREFIX: 'The first reading arrived more than 30 s after the storage session started.',
  MISSING_THRESHOLD: 'No temperature limit was recorded for part of the history.',
  LEGACY_UNATTRIBUTED: 'A gap of more than 30 s was found, but its cause was not recorded.',
};

/** What the gap means for the rating shown. */
export function gapImpact(view: RiskView): string | null {
  if (view.integrity === 'CURRENT_GAP') {
    return 'This reading comes straight after the gap, so the ML model was not used. The rules rated it from incomplete data.';
  }
  if (view.integrity === 'EARLIER_GAP') {
    return (
      'Time above the temperature limit during the gap is not counted, so this rating may be lower than the real ' +
      'risk. Check the zone directly before relying on it.'
    );
  }
  return null;
}

export const MODEL_UNAVAILABLE_TEXT: Partial<Record<QualityFlag, string>> = {
  MODEL_MISSING: 'The trained model is not installed, so the rules rated this reading.',
  MODEL_INCOMPATIBLE: 'The installed model does not match the current rules, so the rules rated this reading.',
  MODEL_ERROR: 'The model failed on this reading, so the rules rated it.',
  UNKNOWN_PRODUCT: 'The model was not trained for this product type, so the rules rated this reading.',
};

export function countHistoryGaps(views: RiskView[]): number {
  return views.filter(hasHistoryGap).length;
}
