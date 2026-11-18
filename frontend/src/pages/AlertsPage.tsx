import { useState, type FormEvent } from 'react';
import { useApi } from '../api/ApiContext';
import { errorMessage } from '../api/http';
import type { Alert, AlertState } from '../api/types';
import { Dialog } from '../components/Dialog';
import { Badge, EmptyRow, ErrorNotice, Notice, PageHeader, Pagination } from '../components/ui';
import { formatDateTime, formatDuration, formatNumber, humanize } from '../domain/format';
import { usePolling } from '../hooks/usePolling';

const PAGE_SIZE = 25;

/**
 * Alert history and acknowledgment (FR-06, FR-08).
 *
 * Lists `GET /api/alerts` (newest opened first, refreshed every 5 s) with state, acknowledgment and zone filters, and
 * acknowledges through `POST /api/alerts/{id}/acknowledgment` with an optional note of up to 500 characters.
 * Acknowledging does not resolve an alert: the backend resolves it when readings recover. The first acknowledgment is
 * final; a repeat returns `ALREADY_ACKNOWLEDGED` and the page says who acknowledged it.
 */
export function AlertsPage() {
  const api = useApi();
  const [state, setState] = useState<AlertState | ''>('');
  const [acknowledged, setAcknowledged] = useState<'' | 'true' | 'false'>('');
  const [deviceId, setDeviceId] = useState('');
  const [page, setPage] = useState(0);
  const [acking, setAcking] = useState<Alert>();
  const [message, setMessage] = useState<string>();

  const devices = usePolling(() => api.thresholds.list(), 0, [api]);
  const alerts = usePolling(
    () =>
      api.alerts.search({
        state: state || undefined,
        acknowledged: acknowledged === '' ? undefined : acknowledged === 'true',
        deviceId: deviceId ? [deviceId] : undefined,
        page,
        size: PAGE_SIZE,
      }),
    5000,
    [api, state, acknowledged, deviceId, page],
  );

  const filter = (apply: () => void) => {
    apply();
    setPage(0);
  };

  return (
    <>
      <PageHeader
        title="Alerts"
        subtitle="Threshold alerts, newest first. Acknowledging records who saw it; the alert resolves on its own when readings recover."
      />
      {message && (
        <Notice tone="ok" onClose={() => setMessage(undefined)}>
          {message}
        </Notice>
      )}
      <div className="filters">
        <label>
          State
          <select value={state} onChange={(e) => filter(() => setState(e.target.value as AlertState | ''))}>
            <option value="">All</option>
            <option value="ACTIVE">Active</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </label>
        <label>
          Acknowledged
          <select
            value={acknowledged}
            onChange={(e) => filter(() => setAcknowledged(e.target.value as '' | 'true' | 'false'))}
          >
            <option value="">All</option>
            <option value="false">Not acknowledged</option>
            <option value="true">Acknowledged</option>
          </select>
        </label>
        <label>
          Zone
          <select value={deviceId} onChange={(e) => filter(() => setDeviceId(e.target.value))}>
            <option value="">All zones</option>
            {devices.data?.map((d) => (
              <option key={d.deviceId} value={d.deviceId}>
                {d.locationCode ?? d.deviceId} ({d.deviceId})
              </option>
            ))}
          </select>
        </label>
      </div>
      <ErrorNotice error={alerts.error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>Zone</th>
              <th>Alert</th>
              <th>Peak</th>
              <th>Opened</th>
              <th>Duration</th>
              <th>State</th>
              <th>Acknowledgment</th>
            </tr>
          </thead>
          <tbody>
            {alerts.data?.items.length === 0 && <EmptyRow colSpan={8}>No alerts match these filters.</EmptyRow>}
            {alerts.data?.items.map((a) => (
              <tr key={a.id} className={a.state === 'ACTIVE' ? 'row-active' : undefined}>
                <td className="mono">{a.id}</td>
                <td className="mono">{a.deviceId}</td>
                <td>
                  <div className="cell-title">
                    <Badge tone={a.severity === 'HIGH' ? 'danger' : 'warn'}>{a.severity}</Badge>{' '}
                    {a.description ?? humanize(a.kind)}
                  </div>
                </td>
                <td className="mono">
                  {a.threshold ? `${formatNumber(a.threshold.peakValue, 2)} ${a.threshold.unit}` : '—'}
                </td>
                <td>{formatDateTime(a.openedAt)}</td>
                <td>{formatDuration(a.openedAt, a.state === 'ACTIVE' ? a.lastSeenAt : a.resolvedAt)}</td>
                <td>
                  {a.state === 'ACTIVE' ? (
                    <Badge tone="danger">Active</Badge>
                  ) : (
                    <Badge tone="muted">{humanize(a.resolveReason ?? 'RESOLVED')}</Badge>
                  )}
                </td>
                <td>
                  {a.acknowledgment ? (
                    <div className="small">
                      <strong>{a.acknowledgment.username}</strong> · {formatDateTime(a.acknowledgment.at)}
                      {a.acknowledgment.note && <div className="muted">“{a.acknowledgment.note}”</div>}
                    </div>
                  ) : (
                    <button className="btn btn-sm btn-primary" onClick={() => setAcking(a)}>
                      Acknowledge
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pagination page={alerts.data} onChange={setPage} />
      {acking && (
        <AcknowledgeDialog
          alert={acking}
          onClose={() => setAcking(undefined)}
          onDone={(text) => {
            setAcking(undefined);
            setMessage(text);
            alerts.refresh();
          }}
        />
      )}
    </>
  );
}

function AcknowledgeDialog({
  alert,
  onClose,
  onDone,
}: {
  alert: Alert;
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const api = useApi();
  const [note, setNote] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      const result = await api.alerts.acknowledge(alert.id, note.trim() || undefined);
      onDone(
        result.outcome === 'ACKNOWLEDGED'
          ? `Alert #${alert.id} acknowledged.`
          : `Alert #${alert.id} was already acknowledged by ${result.alert.acknowledgment?.username}.`,
      );
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <Dialog title={`Acknowledge alert #${alert.id}`} open onClose={onClose}>
      <form onSubmit={submit} className="form">
        <p className="muted">{alert.description}</p>
        {error && <Notice tone="danger">{error}</Notice>}
        <label>
          Note (optional)
          <textarea
            value={note}
            maxLength={500}
            rows={3}
            onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. Door left open during loading; closed at 14:05."
          />
          <span className="hint">{note.length}/500</span>
        </label>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            Acknowledge
          </button>
        </div>
      </form>
    </Dialog>
  );
}
