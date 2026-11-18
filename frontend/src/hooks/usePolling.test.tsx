import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { usePolling } from './usePolling';

afterEach(() => vi.useRealTimers());

describe('usePolling', () => {
  it('loads immediately and again after each interval', async () => {
    vi.useFakeTimers();
    let n = 0;
    const load = vi.fn(async () => ++n);
    const { result } = renderHook(() => usePolling(load, 2000, []));
    await act(async () => {});
    expect(result.current.data).toBe(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(result.current.data).toBe(2);
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('keeps the last data when a poll fails and reports the error', async () => {
    vi.useFakeTimers();
    const load = vi
      .fn()
      .mockResolvedValueOnce('first')
      .mockRejectedValueOnce(new Error('down'))
      .mockResolvedValue('back');
    const { result } = renderHook(() => usePolling(load, 1000, []));
    await act(async () => {});
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(result.current.data).toBe('first');
    expect((result.current.error as Error).message).toBe('down');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(result.current.data).toBe('back');
    expect(result.current.error).toBeUndefined();
  });

  it('stops polling on unmount and loads once with interval 0', async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValue(1);
    const { unmount } = renderHook(() => usePolling(load, 0, []));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(load).toHaveBeenCalledTimes(1);
    unmount();
  });

  it('restarts when dependencies change', async () => {
    const load = vi.fn(async () => 'x');
    const { rerender } = renderHook(({ dep }) => usePolling(load, 0, [dep]), { initialProps: { dep: 1 } });
    await waitFor(() => expect(load).toHaveBeenCalledTimes(1));
    rerender({ dep: 2 });
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
  });
});
