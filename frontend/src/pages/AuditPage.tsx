import { useState, type FormEvent } from 'react';
import { useApi } from '../api/ApiContext';
import { AUDIT_EVENT_TYPES } from '../api/types';
import { Badge, EmptyRow, ErrorNotice, PageHeader, Pagination } from '../components/ui';
import { formatDateTime, humanize, localInputToIso } from '../domain/format';
import { usePolling } from '../hooks/usePolling';

interface Filters {
  from: string;
  to: string;
  actor: string;
  eventType: string;
  result: string;
}

const EMPTY: Filters = { from: '', to: '', actor: '', eventType: '', result: '' };

/**
 * Audit review for administrators (FR-07, FR-08).
 *
 * Read-only view of `GET /api/audit-events`, filtered by time range, actor, event type and result, 50 per page. The
 * backend has no endpoint that edits or deletes audit records. Times typed into the filters are browser local time
 * and sent as UTC instants.
 */
export function AuditPage() {
  const api = useApi();
  const [draft, setDraft] = useState<Filters>(EMPTY);
  const [applied, setApplied] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(0);

  const events = usePolling(
    () =>
      api.audit.search({
        from: localInputToIso(applied.from),
        to: localInputToIso(applied.to),
        actor: applied.actor.trim() || undefined,
        eventType: applied.eventType ? [applied.eventType] : undefined,
        result: applied.result ? [applied.result] : undefined,
        page,
        size: 50,
      }),
    0,
    [api, applied, page],
  );

  const apply = (e: FormEvent) => {
    e.preventDefault();
    setApplied(draft);
    setPage(0);
  };

  const set = (key: keyof Filters) => (e: { target: { value: string } }) =>
    setDraft({ ...draft, [key]: e.target.value });

  return (
    <>
      <PageHeader
        title="Audit log"
        subtitle="Who did what and when. Records cannot be edited or deleted."
        actions={
          <button className="btn" onClick={events.refresh}>
            Refresh
          </button>
        }
      />
      <form className="filters" onSubmit={apply}>
        <label>
          From
          <input type="datetime-local" value={draft.from} onChange={set('from')} />
        </label>
        <label>
          To
          <input type="datetime-local" value={draft.to} onChange={set('to')} />
        </label>
        <label>
          Actor
          <input value={draft.actor} onChange={set('actor')} placeholder="username" />
        </label>
        <label>
          Event
          <select value={draft.eventType} onChange={set('eventType')}>
            <option value="">All events</option>
            {AUDIT_EVENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {humanize(t)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Result
          <select value={draft.result} onChange={set('result')}>
            <option value="">All</option>
            <option value="SUCCESS">Success</option>
            <option value="FAILURE">Failure</option>
            <option value="DENIED">Denied</option>
          </select>
        </label>
        <div className="filter-buttons">
          <button className="btn btn-primary" type="submit">
            Apply
          </button>
          <button
            className="btn"
            type="button"
            onClick={() => {
              setDraft(EMPTY);
              setApplied(EMPTY);
              setPage(0);
            }}
          >
            Clear
          </button>
        </div>
      </form>
      <ErrorNotice error={events.error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>Event</th>
              <th>Result</th>
              <th>Actor</th>
              <th>Target</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {events.data?.items.length === 0 && <EmptyRow colSpan={6}>No audit events match these filters.</EmptyRow>}
            {events.data?.items.map((e) => (
              <tr key={e.id}>
                <td className="nowrap">{formatDateTime(e.occurredAt)}</td>
                <td>{humanize(e.eventType)}</td>
                <td>
                  <Badge tone={e.result === 'SUCCESS' ? 'ok' : 'danger'}>{humanize(e.result)}</Badge>
                </td>
                <td>
                  {e.actorUsername ??
                    (e.attemptedUsername ? (
                      <span className="muted">{e.attemptedUsername} (attempted)</span>
                    ) : (
                      <span className="muted">system</span>
                    ))}
                </td>
                <td className="mono small">{e.targetType ? `${e.targetType} ${e.targetId ?? ''}` : '—'}</td>
                <td className="details">{e.details ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pagination page={events.data} onChange={setPage} />
    </>
  );
}
