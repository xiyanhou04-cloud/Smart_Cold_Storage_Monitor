import type { ReactNode } from 'react';
import { errorMessage } from '../api/http';
import type { Page } from '../api/types';

/** Small presentational building blocks shared by every page. They hold no state and call no API. */

export type Tone = 'ok' | 'warn' | 'danger' | 'muted' | 'info';

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function ErrorNotice({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <div className="notice notice-danger" role="alert">
      {errorMessage(error)}
    </div>
  );
}

export function Notice({
  tone,
  children,
  onClose,
}: {
  tone: 'ok' | 'danger' | 'info';
  children: ReactNode;
  onClose?: () => void;
}) {
  return (
    <div className={`notice notice-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <span>{children}</span>
      {onClose && (
        <button className="notice-close" onClick={onClose} aria-label="Dismiss">
          ×
        </button>
      )}
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function Pagination<T>({ page, onChange }: { page: Page<T> | undefined; onChange: (page: number) => void }) {
  if (!page || page.totalPages <= 1) {
    return page ? <div className="pagination muted">{page.totalItems} item(s)</div> : null;
  }
  return (
    <div className="pagination">
      <button className="btn btn-sm" disabled={page.page === 0} onClick={() => onChange(page.page - 1)}>
        Previous
      </button>
      <span>
        Page {page.page + 1} of {page.totalPages} · {page.totalItems} item(s)
      </span>
      <button
        className="btn btn-sm"
        disabled={page.page + 1 >= page.totalPages}
        onClick={() => onChange(page.page + 1)}
      >
        Next
      </button>
    </div>
  );
}

export function EmptyRow({ colSpan, children }: { colSpan: number; children: ReactNode }) {
  return (
    <tr>
      <td colSpan={colSpan} className="empty">
        {children}
      </td>
    </tr>
  );
}
