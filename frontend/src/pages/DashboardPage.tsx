import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useApi } from '../api/ApiContext';
import type { ReadingsByDevice } from '../api/types';
import { DeviceCard } from '../components/DeviceCard';
import { TrendChart } from '../components/TrendChart';
import { Badge, ErrorNotice, PageHeader } from '../components/ui';
import { formatClock, formatDuration } from '../domain/format';
import { buildSnapshots, mergeSeries, summarize } from '../domain/status';
import { usePolling } from '../hooks/usePolling';

/** FR-03: the dashboard refreshes every 2 seconds. */
export const DASHBOARD_POLL_MS = 2000;
/** Readings window for the device cards; a device with nothing in it shows "No data". */
const CARD_WINDOW = '2m';
const WINDOWS = [
  { value: '5m', label: '5 min' },
  { value: '15m', label: '15 min' },
  { value: '1h', label: '1 h' },
  { value: '6h', label: '6 h' },
];

/**
 * Live zone overview (FR-03).
 *
 * Three polls run side by side: the latest readings of every device (`GET /api/readings/query`, last 2 minutes,
 * every 2 s), the active alerts (`GET /api/alerts?state=ACTIVE`, every 2 s) and the limits (`GET
 * /api/settings/thresholds`, every 10 s, since they rarely change). `buildSnapshots` joins them into one card per
 * zone. The trend chart polls the selected device separately for the chosen window (at most 6 hours, the backend's
 * limit). When a poll fails, the last data stays on screen and the header shows "Connection lost".
 */
export function DashboardPage() {
  const api = useApi();
  const [selected, setSelected] = useState<string>();
  const [window, setWindow] = useState('15m');

  const readings = usePolling(() => api.readings.query({ window: CARD_WINDOW }), DASHBOARD_POLL_MS, [api]);
  const thresholds = usePolling(() => api.thresholds.list(), 10_000, [api]);
  const alerts = usePolling(() => api.alerts.search({ state: 'ACTIVE', size: 200 }), DASHBOARD_POLL_MS, [api]);

  const snapshots = useMemo(
    () => buildSnapshots(readings.data, thresholds.data, alerts.data?.items, Date.now()),
    // updatedAt changes on every successful poll, so ages are recomputed each time.
    [readings.data, thresholds.data, alerts.data, readings.updatedAt],
  );
  const deviceId = selected ?? snapshots[0]?.deviceId;
  const current = snapshots.find((s) => s.deviceId === deviceId);

  const history = usePolling(
    () => (deviceId ? api.readings.query({ deviceId: [deviceId], window }) : Promise.resolve<ReadingsByDevice>({})),
    DASHBOARD_POLL_MS,
    [api, deviceId, window],
  );
  const points = useMemo(() => mergeSeries(deviceId ? history.data?.[deviceId] : undefined), [history.data, deviceId]);

  const counts = summarize(snapshots);
  const activeAlerts = alerts.data?.items ?? [];
  const unacked = activeAlerts.filter((a) => !a.acknowledgment).length;
  const connectionLost = Boolean(readings.error) && readings.data !== undefined;

  return (
    <>
      <PageHeader
        title="Zone overview"
        subtitle={
          <>
            Live readings, refreshed every {DASHBOARD_POLL_MS / 1000} s
            {readings.updatedAt && <> · last update {formatClock(readings.updatedAt.getTime())}</>}
          </>
        }
        actions={
          connectionLost ? (
            <Badge tone="danger">Connection lost, showing last data</Badge>
          ) : (
            <Badge tone="ok">● Live</Badge>
          )
        }
      />
      {!readings.data && <ErrorNotice error={readings.error} />}

      <section className="summary-row" aria-label="Summary">
        <SummaryTile label="Zones in range" value={counts.OK} total={snapshots.length} tone="ok" />
        <SummaryTile label="Excursions" value={counts.EXCURSION} tone={counts.EXCURSION ? 'danger' : 'muted'} />
        <SummaryTile
          label="Stale or no data"
          value={counts.STALE + counts.NO_DATA}
          tone={counts.STALE + counts.NO_DATA ? 'warn' : 'muted'}
        />
        <SummaryTile
          label="Active alerts"
          value={activeAlerts.length}
          sub={unacked ? `${unacked} unacknowledged` : undefined}
          tone={activeAlerts.length ? 'danger' : 'muted'}
        />
      </section>

      <section className="device-grid" aria-label="Zones">
        {readings.loading && snapshots.length === 0 && <div className="muted">Loading zones…</div>}
        {snapshots.map((s) => (
          <DeviceCard
            key={s.deviceId}
            snapshot={s}
            selected={s.deviceId === deviceId}
            onSelect={() => setSelected(s.deviceId)}
          />
        ))}
      </section>

      {current && (
        <section className="panel">
          <div className="panel-head">
            <div>
              <h2>
                {current.locationCode ?? current.deviceId} <span className="muted">· {current.deviceId}</span>
              </h2>
              <p className="muted small">Shaded band = allowed range. Readings outside it open a threshold alert.</p>
            </div>
            <div className="segmented" role="group" aria-label="Time window">
              {WINDOWS.map((w) => (
                <button
                  key={w.value}
                  className={w.value === window ? 'active' : undefined}
                  onClick={() => setWindow(w.value)}
                >
                  {w.label}
                </button>
              ))}
            </div>
          </div>
          <ErrorNotice error={history.error} />
          <div className="chart-grid">
            <TrendChart metric="temperature" points={points} threshold={current.threshold} />
            <TrendChart metric="humidity" points={points} threshold={current.threshold} />
          </div>
        </section>
      )}

      <section className="panel">
        <div className="panel-head">
          <h2>Active alerts</h2>
          <Link to="/alerts" className="btn btn-sm">
            Open alert history
          </Link>
        </div>
        {activeAlerts.length === 0 ? (
          <p className="muted">No active alerts.</p>
        ) : (
          <ul className="alert-list">
            {activeAlerts.slice(0, 8).map((a) => (
              <li key={a.id}>
                <Badge tone="danger">{a.severity}</Badge>
                <span className="mono">{a.deviceId}</span>
                <span className="grow">{a.description ?? a.kind}</span>
                <span className="muted small">open {formatDuration(a.openedAt)}</span>
                {a.acknowledgment ? (
                  <Badge tone="muted">Acked by {a.acknowledgment.username}</Badge>
                ) : (
                  <Badge tone="warn">Unacknowledged</Badge>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

function SummaryTile({
  label,
  value,
  total,
  sub,
  tone,
}: {
  label: string;
  value: number;
  total?: number;
  sub?: string;
  tone: string;
}) {
  return (
    <div className={`summary-tile tone-${tone}`}>
      <div className="summary-label">{label}</div>
      <div className="summary-value">
        {value}
        {total !== undefined && <span className="summary-total"> / {total}</span>}
      </div>
      {sub && <div className="summary-sub">{sub}</div>}
    </div>
  );
}
