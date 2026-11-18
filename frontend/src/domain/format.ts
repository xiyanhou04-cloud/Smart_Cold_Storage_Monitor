/** Display formatting shared by every page. */

export function formatNumber(value: number | null | undefined, digits = 1): string {
  return value == null || Number.isNaN(value) ? '—' : value.toFixed(digits);
}

export function formatTemperature(value: number | null | undefined, digits = 1): string {
  return value == null ? '—' : `${formatNumber(value, digits)} °C`;
}

export function formatHumidity(value: number | null | undefined, digits = 1): string {
  return value == null ? '—' : `${formatNumber(value, digits)} %RH`;
}

export function formatRange(lower: number | null | undefined, upper: number | null | undefined, unit: string): string {
  if (lower == null || upper == null) return '—';
  return `${formatNumber(lower)} to ${formatNumber(upper)} ${unit}`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function formatClock(ms: number): string {
  return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/** "3 s ago", "4 min ago", "2 h ago". */
export function formatAge(ms: number | undefined): string {
  if (ms === undefined) return 'no data';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min ago`;
  return `${Math.round(m / 60)} h ago`;
}

/** Duration between two instants, e.g. "2 min 5 s". End defaults to now. */
export function formatDuration(fromIso: string, toIso?: string | null, now = Date.now()): string {
  const end = toIso ? Date.parse(toIso) : now;
  const total = Math.max(0, Math.round((end - Date.parse(fromIso)) / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h} h ${m} min`;
  if (m > 0) return `${m} min ${s} s`;
  return `${s} s`;
}

/** "THRESHOLD_CHANGE_REQUESTED" → "Threshold change requested". */
export function humanize(code: string | null | undefined): string {
  if (!code) return '—';
  const text = code.toLowerCase().replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Converts a `<input type="datetime-local">` value (browser local time) to a UTC ISO instant. */
export function localInputToIso(value: string): string | undefined {
  if (!value) return undefined;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString();
}
