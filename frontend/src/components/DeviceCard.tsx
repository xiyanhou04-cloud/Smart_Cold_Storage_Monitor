import type { DeviceSnapshot, DeviceStatus, RangeCheck } from '../domain/status';
import { formatAge, formatHumidity, formatRange, formatTemperature } from '../domain/format';
import { hasHistoryGap, type RiskView } from '../domain/risk';
import type { RiskLevel } from '../api/types';
import { Badge, type Tone } from './ui';

export const RISK_TONE: Record<RiskLevel, Tone> = { LOW: 'ok', MEDIUM: 'warn', HIGH: 'danger' };

const STATUS: Record<DeviceStatus, { tone: Tone; label: string }> = {
  OK: { tone: 'ok', label: 'In range' },
  EXCURSION: { tone: 'danger', label: 'Excursion' },
  STALE: { tone: 'warn', label: 'Stale data' },
  NO_DATA: { tone: 'muted', label: 'No data' },
};

function checkClass(check: RangeCheck, stale: boolean): string {
  if (stale) return 'metric-value stale';
  return check === 'ABOVE' || check === 'BELOW' ? 'metric-value out' : 'metric-value';
}

/** Out-of-range values get two decimals, so 4.03 °C is not shown as "4.0 °C" next to a 4.0 °C limit. */
function digits(check: RangeCheck): number {
  return check === 'ABOVE' || check === 'BELOW' ? 2 : 1;
}

function checkLabel(check: RangeCheck): string | null {
  if (check === 'ABOVE') return '▲ above limit';
  if (check === 'BELOW') return '▼ below limit';
  return null;
}

/**
 * One zone on the dashboard: latest temperature and humidity against the zone's limits, freshness, and active alerts
 * (FR-03). The status comes from `buildSnapshots` in `domain/status.ts`; a stale card greys its values out because
 * they may no longer describe the zone. The risk row shows the latest prediction (FR-05) and flags a rating that
 * rests on incomplete history, so a LOW after a gap is not read as "safe".
 */
export function DeviceCard({
  snapshot,
  risk,
  selected,
  onSelect,
}: {
  snapshot: DeviceSnapshot;
  risk?: RiskView;
  selected: boolean;
  onSelect: () => void;
}) {
  const s = snapshot;
  const status = STATUS[s.status];
  const stale = s.freshness !== 'LIVE';
  const t = s.threshold;
  const gap = risk ? hasHistoryGap(risk) : false;
  const riskText = risk?.state === 'PREDICTED' ? ` risk ${risk.level}${gap ? ' incomplete history' : ''}` : '';
  return (
    <button
      type="button"
      className={`device-card status-${s.status.toLowerCase()}${selected ? ' selected' : ''}`}
      onClick={onSelect}
      aria-pressed={selected}
      aria-label={`${s.locationCode ?? s.deviceId} ${status.label}${riskText}`}
    >
      <div className="device-card-head">
        <div>
          <div className="device-zone">{s.locationCode ?? '—'}</div>
          <div className="device-id">{s.deviceId}</div>
        </div>
        <Badge tone={status.tone}>{status.label}</Badge>
      </div>
      <div className="device-metrics">
        <div>
          <div className={checkClass(s.temperatureCheck, stale)}>
            {formatTemperature(s.temperature?.value, digits(s.temperatureCheck))}
          </div>
          <div className="metric-sub">
            {checkLabel(s.temperatureCheck) ?? formatRange(t?.temperatureLowerC, t?.temperatureUpperC, '°C')}
          </div>
        </div>
        <div>
          <div className={checkClass(s.humidityCheck, stale)}>
            {formatHumidity(s.humidity?.value, digits(s.humidityCheck))}
          </div>
          <div className="metric-sub">
            {checkLabel(s.humidityCheck) ?? formatRange(t?.humidityLowerPct, t?.humidityUpperPct, '%RH')}
          </div>
        </div>
      </div>
      {risk && risk.state !== 'NONE' && (
        <div className="risk-row">
          <span className="risk-label">Risk</span>
          {risk.state === 'PENDING' ? (
            <Badge tone="muted">Pending</Badge>
          ) : (
            <>
              <Badge tone={RISK_TONE[risk.level!]}>{risk.level}</Badge>
              <span className="muted">{risk.source === 'MODEL' ? 'ML model' : 'Rules'}</span>
              {gap && (
                <Badge tone="warn">
                  <span aria-hidden="true">⚠</span> Incomplete history
                </Badge>
              )}
            </>
          )}
        </div>
      )}
      <div className="device-card-foot">
        <span className={stale ? 'warn-text' : 'muted'}>Updated {formatAge(s.ageMs)}</span>
        {s.activeAlerts > 0 && (
          <span className="danger-text">
            {s.activeAlerts} active alert{s.activeAlerts > 1 ? 's' : ''}
            {s.unacknowledgedAlerts > 0 ? ` · ${s.unacknowledgedAlerts} unacked` : ''}
          </span>
        )}
      </div>
    </button>
  );
}
