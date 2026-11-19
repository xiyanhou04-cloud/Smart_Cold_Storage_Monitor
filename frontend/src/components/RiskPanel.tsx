import { formatDateTime } from '../domain/format';
import {
  GAP_REASON_TEXT,
  MODEL_UNAVAILABLE_TEXT,
  gapImpact,
  hasHistoryGap,
  sourceLabel,
  type RiskView,
} from '../domain/risk';
import { RISK_TONE } from './DeviceCard';
import { Badge, Notice } from './ui';

/**
 * Latest risk prediction for the selected zone (FR-05): level, where it came from, and, when the reading history has a
 * gap, why and what that means for the rating. The gap notice is the point of this panel: a rating computed from
 * incomplete history must not be read as a full assessment.
 */
export function RiskPanel({ view }: { view: RiskView }) {
  if (view.state === 'NONE') return null;
  if (view.state === 'PENDING') {
    return <p className="muted small">Risk prediction pending for the latest reading.</p>;
  }
  const impact = gapImpact(view);
  return (
    <section aria-label="Risk prediction">
      <dl className="risk-panel">
        <div>
          <dt>Risk level</dt>
          <dd>
            <Badge tone={RISK_TONE[view.level!]}>{view.level}</Badge>
          </dd>
        </div>
        <div>
          <dt>Prediction source</dt>
          <dd>{sourceLabel(view)}</dd>
        </div>
        <div>
          <dt>Version</dt>
          <dd className="mono small">{view.version}</dd>
        </div>
        <div>
          <dt>Predicted at</dt>
          <dd>{formatDateTime(view.predictedAt)}</dd>
        </div>
        <div>
          <dt>History</dt>
          <dd>
            {hasHistoryGap(view) ? (
              <Badge tone="warn">Incomplete</Badge>
            ) : view.integrity === 'NOT_RECORDED' ? (
              <Badge tone="muted">Not recorded</Badge>
            ) : (
              <Badge tone="ok">Complete</Badge>
            )}
          </dd>
        </div>
      </dl>
      {hasHistoryGap(view) && (
        <Notice tone="warn">
          <span className="gap-notice">
            <strong>
              {view.integrity === 'CURRENT_GAP'
                ? 'Reading history has a gap just before this reading'
                : 'Reading history has a gap earlier in this storage session'}
            </strong>
            <p>{GAP_REASON_TEXT[view.gapReason ?? 'LEGACY_UNATTRIBUTED']}</p>
            {impact && <p>{impact}</p>}
          </span>
        </Notice>
      )}
      {view.modelUnavailable && <Notice tone="info">{MODEL_UNAVAILABLE_TEXT[view.modelUnavailable]}</Notice>}
      {view.integrity === 'NOT_RECORDED' && (
        <p className="muted small">This prediction was stored before history completeness was recorded.</p>
      )}
      {view.stale && <p className="warn-text small">This prediction is for a reading older than 30 s.</p>}
    </section>
  );
}
