import { useCallback, useEffect, useRef, useState } from 'react';

export interface PollingResult<T> {
  data: T | undefined;
  error: unknown;
  loading: boolean;
  /** When the last successful response arrived (browser clock). */
  updatedAt: Date | undefined;
  refresh(): void;
}

/**
 * Calls `load` now and then every `intervalMs` (FR-03: the dashboard refreshes every 2 seconds). A new call starts
 * only after the previous one settled, so a slow backend is never flooded. The latest data is kept when a poll fails,
 * and `error` reports the failure until the next success. Changing `deps` restarts the cycle immediately.
 *
 * Pass `intervalMs = 0` to load once (with manual `refresh`).
 */
export function usePolling<T>(load: () => Promise<T>, intervalMs: number, deps: readonly unknown[]): PollingResult<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date>();
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setLoading(true);

    const run = async () => {
      try {
        const result = await loadRef.current();
        if (cancelled) return;
        setData(result);
        setError(undefined);
        setUpdatedAt(new Date());
      } catch (e) {
        if (!cancelled) setError(e);
      } finally {
        if (!cancelled) {
          setLoading(false);
          if (intervalMs > 0) timer = setTimeout(run, intervalMs);
        }
      }
    };
    run();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, tick, ...deps]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, loading, updatedAt, refresh };
}
