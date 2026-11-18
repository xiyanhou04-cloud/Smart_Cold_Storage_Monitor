import { useState, type FormEvent } from 'react';
import { useApi } from '../api/ApiContext';
import { errorMessage } from '../api/http';
import type { DeviceThreshold, ThresholdLimits, ThresholdRequest, ThresholdRequestStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Dialog } from '../components/Dialog';
import { Badge, EmptyRow, ErrorNotice, Notice, PageHeader, Pagination, type Tone } from '../components/ui';
import { formatDateTime, formatRange, humanize } from '../domain/format';
import { usePolling } from '../hooks/usePolling';

const STATUS_TONE: Record<ThresholdRequestStatus, Tone> = {
  PENDING: 'warn',
  APPROVED: 'ok',
  REJECTED: 'danger',
  CANCELLED: 'muted',
  SUPERSEDED: 'muted',
};

/**
 * Same rules as the backend's `ThresholdLimits` record (temperature -40 to 125 °C, humidity 0 to 100 %RH,
 * each lower limit below its upper), checked here so a typo is reported before a request. The backend still checks.
 */
export function validateLimits(l: ThresholdLimits): string | undefined {
  const values = Object.values(l);
  if (values.some((v) => !Number.isFinite(v))) return 'All four limits are required.';
  if (l.temperatureLowerC < -40 || l.temperatureUpperC > 125)
    return 'Temperature limits must be between -40 and 125 °C.';
  if (l.humidityLowerPct < 0 || l.humidityUpperPct > 100) return 'Humidity limits must be between 0 and 100 %RH.';
  if (l.temperatureLowerC >= l.temperatureUpperC) return 'The lower temperature limit must be below the upper limit.';
  if (l.humidityLowerPct >= l.humidityUpperPct) return 'The lower humidity limit must be below the upper limit.';
  return undefined;
}

/**
 * Threshold settings per device and change requests (FR-04).
 *
 * The table is `GET /api/settings/thresholds`. Saving calls `PUT /api/settings/thresholds/{deviceId}`: an
 * administrator's change applies at once (`APPLIED`); an operator's becomes a request (`PENDING_APPROVAL`) and the
 * limits stay until an administrator approves it. Administrators approve or reject pending requests with an
 * optional note; a requester can cancel their own. Operators see only their own requests (enforced by the backend).
 */
export function ThresholdsPage() {
  const api = useApi();
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';
  const [editing, setEditing] = useState<DeviceThreshold>();
  const [reviewing, setReviewing] = useState<{ request: ThresholdRequest; action: 'approve' | 'reject' }>();
  const [message, setMessage] = useState<{ tone: 'ok' | 'danger'; text: string }>();
  const [statusFilter, setStatusFilter] = useState<ThresholdRequestStatus | ''>('PENDING');
  const [page, setPage] = useState(0);

  const thresholds = usePolling(() => api.thresholds.list(), 10_000, [api]);
  const requests = usePolling(
    () => api.thresholds.requests({ status: statusFilter ? [statusFilter] : undefined, page, size: 20 }),
    10_000,
    [api, statusFilter, page],
  );

  const reloadAll = (text: string) => {
    setMessage({ tone: 'ok', text });
    thresholds.refresh();
    requests.refresh();
  };

  const cancel = async (r: ThresholdRequest) => {
    try {
      await api.thresholds.cancel(r.id);
      reloadAll(`Request #${r.id} cancelled.`);
    } catch (err) {
      setMessage({ tone: 'danger', text: errorMessage(err) });
    }
  };

  return (
    <>
      <PageHeader
        title="Threshold settings"
        subtitle={
          isAdmin
            ? 'Changes apply to the next reading of each zone.'
            : 'Your changes are sent to an administrator for approval. The current limits stay until then.'
        }
      />
      {message && (
        <Notice tone={message.tone} onClose={() => setMessage(undefined)}>
          {message.text}
        </Notice>
      )}
      <ErrorNotice error={thresholds.error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Zone</th>
              <th>Device</th>
              <th>Temperature</th>
              <th>Humidity</th>
              <th>Source</th>
              <th>Effective from</th>
              <th>Pending request</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {thresholds.data?.length === 0 && <EmptyRow colSpan={8}>No devices registered.</EmptyRow>}
            {thresholds.data?.map((t) => (
              <tr key={t.deviceId}>
                <td>
                  <strong>{t.locationCode ?? '—'}</strong>
                </td>
                <td className="mono">{t.deviceId}</td>
                <td>{formatRange(t.temperatureLowerC, t.temperatureUpperC, '°C')}</td>
                <td>{formatRange(t.humidityLowerPct, t.humidityUpperPct, '%RH')}</td>
                <td>
                  {t.source === 'CUSTOM' ? (
                    <Badge tone="info">Custom · {t.changedBy}</Badge>
                  ) : t.source ? (
                    <Badge tone="muted">Default</Badge>
                  ) : (
                    '—'
                  )}
                </td>
                <td>{formatDateTime(t.effectiveFrom)}</td>
                <td>
                  {t.pendingRequest ? (
                    <span className="small">
                      <Badge tone="warn">#{t.pendingRequest.id}</Badge> by {t.pendingRequest.requestedBy}
                    </span>
                  ) : (
                    <span className="muted">—</span>
                  )}
                </td>
                <td className="actions">
                  <button className="btn btn-sm" disabled={t.sessionId == null} onClick={() => setEditing(t)}>
                    {isAdmin ? 'Edit' : 'Request change'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <section className="panel">
        <div className="panel-head">
          <h2>{isAdmin ? 'Change requests' : 'My change requests'}</h2>
          <label className="inline">
            Status
            <select
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value as ThresholdRequestStatus | '');
                setPage(0);
              }}
            >
              <option value="">All</option>
              {Object.keys(STATUS_TONE).map((s) => (
                <option key={s} value={s}>
                  {humanize(s)}
                </option>
              ))}
            </select>
          </label>
        </div>
        <ErrorNotice error={requests.error} />
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Device</th>
                <th>Proposed temperature</th>
                <th>Proposed humidity</th>
                <th>Requested</th>
                <th>Status</th>
                <th>Review</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {requests.data?.items.length === 0 && <EmptyRow colSpan={8}>No requests.</EmptyRow>}
              {requests.data?.items.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{r.id}</td>
                  <td className="mono">{r.deviceId}</td>
                  <td>{formatRange(r.temperatureLowerC, r.temperatureUpperC, '°C')}</td>
                  <td>{formatRange(r.humidityLowerPct, r.humidityUpperPct, '%RH')}</td>
                  <td className="small">
                    {r.requestedBy} · {formatDateTime(r.requestedAt)}
                  </td>
                  <td>
                    <Badge tone={STATUS_TONE[r.status]}>{humanize(r.status)}</Badge>
                  </td>
                  <td className="small">
                    {r.reviewedBy ? `${r.reviewedBy} · ${formatDateTime(r.reviewedAt)}` : '—'}
                    {r.reviewNote && <div className="muted">“{r.reviewNote}”</div>}
                  </td>
                  <td className="actions">
                    {r.status === 'PENDING' && isAdmin && (
                      <>
                        <button
                          className="btn btn-sm btn-primary"
                          onClick={() => setReviewing({ request: r, action: 'approve' })}
                        >
                          Approve
                        </button>
                        <button
                          className="btn btn-sm btn-danger"
                          onClick={() => setReviewing({ request: r, action: 'reject' })}
                        >
                          Reject
                        </button>
                      </>
                    )}
                    {r.status === 'PENDING' && r.requestedBy === user?.username && (
                      <button className="btn btn-sm" onClick={() => void cancel(r)}>
                        Cancel
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Pagination page={requests.data} onChange={setPage} />
      </section>

      {editing && (
        <EditLimitsDialog
          device={editing}
          isAdmin={isAdmin}
          onClose={() => setEditing(undefined)}
          onDone={(text) => {
            setEditing(undefined);
            reloadAll(text);
          }}
        />
      )}
      {reviewing && (
        <ReviewDialog
          {...reviewing}
          onClose={() => setReviewing(undefined)}
          onDone={(text) => {
            setReviewing(undefined);
            reloadAll(text);
          }}
        />
      )}
    </>
  );
}

function EditLimitsDialog({
  device,
  isAdmin,
  onClose,
  onDone,
}: {
  device: DeviceThreshold;
  isAdmin: boolean;
  onClose: () => void;
  onDone: (m: string) => void;
}) {
  const api = useApi();
  const [form, setForm] = useState({
    temperatureLowerC: String(device.temperatureLowerC ?? ''),
    temperatureUpperC: String(device.temperatureUpperC ?? ''),
    humidityLowerPct: String(device.humidityLowerPct ?? ''),
    humidityUpperPct: String(device.humidityUpperPct ?? ''),
  });
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const field = (key: keyof typeof form, label: string) => (
    <label>
      {label}
      <input
        type="number"
        step="0.1"
        value={form[key]}
        onChange={(e) => setForm({ ...form, [key]: e.target.value })}
        required
      />
    </label>
  );

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const limits: ThresholdLimits = {
      temperatureLowerC: parseFloat(form.temperatureLowerC),
      temperatureUpperC: parseFloat(form.temperatureUpperC),
      humidityLowerPct: parseFloat(form.humidityLowerPct),
      humidityUpperPct: parseFloat(form.humidityUpperPct),
    };
    const invalid = validateLimits(limits);
    if (invalid) {
      setError(invalid);
      return;
    }
    setBusy(true);
    try {
      const result = await api.thresholds.submit(device.deviceId, limits);
      onDone(
        result.outcome === 'APPLIED'
          ? `New limits for ${device.deviceId} are in effect.`
          : result.outcome === 'UNCHANGED'
            ? 'These are already the current limits; nothing changed.'
            : `Request #${result.request?.id} sent for administrator approval.`,
      );
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <Dialog
      title={`${isAdmin ? 'Edit' : 'Request'} limits · ${device.locationCode ?? device.deviceId}`}
      open
      onClose={onClose}
    >
      <form className="form" onSubmit={submit}>
        {error && <Notice tone="danger">{error}</Notice>}
        <div className="form-grid">
          {field('temperatureLowerC', 'Temperature lower (°C)')}
          {field('temperatureUpperC', 'Temperature upper (°C)')}
          {field('humidityLowerPct', 'Humidity lower (%RH)')}
          {field('humidityUpperPct', 'Humidity upper (%RH)')}
        </div>
        <p className="hint">A reading equal to a limit counts as in range.</p>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {isAdmin ? 'Apply limits' : 'Submit request'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function ReviewDialog({
  request,
  action,
  onClose,
  onDone,
}: {
  request: ThresholdRequest;
  action: 'approve' | 'reject';
  onClose: () => void;
  onDone: (m: string) => void;
}) {
  const api = useApi();
  const [note, setNote] = useState('');
  const [error, setError] = useState<string>();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const fn = action === 'approve' ? api.thresholds.approve : api.thresholds.reject;
      await fn(request.id, note.trim() || undefined);
      onDone(`Request #${request.id} ${action === 'approve' ? 'approved' : 'rejected'}.`);
    } catch (err) {
      setError(errorMessage(err));
    }
  };
  return (
    <Dialog title={`${action === 'approve' ? 'Approve' : 'Reject'} request #${request.id}`} open onClose={onClose}>
      <form className="form" onSubmit={submit}>
        {error && <Notice tone="danger">{error}</Notice>}
        <p>
          {request.deviceId}: {formatRange(request.temperatureLowerC, request.temperatureUpperC, '°C')},{' '}
          {formatRange(request.humidityLowerPct, request.humidityUpperPct, '%RH')} (requested by {request.requestedBy})
        </p>
        <label>
          Note (optional)
          <textarea value={note} maxLength={500} rows={2} onChange={(e) => setNote(e.target.value)} />
        </label>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className={`btn ${action === 'approve' ? 'btn-primary' : 'btn-danger'}`}>
            {action === 'approve' ? 'Approve' : 'Reject'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
