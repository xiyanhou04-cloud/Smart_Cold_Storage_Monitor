import { describe, expect, it, vi } from 'vitest';
import { ApiError, buildQuery, errorMessage, HttpClient } from './http';

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const csrf = (token: string) => json(200, { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token });

describe('buildQuery', () => {
  it('drops empty values and comma-joins arrays', () => {
    expect(buildQuery({ a: 1, b: undefined, c: '', d: ['x', 'y'], e: [], f: false })).toBe('?a=1&d=x%2Cy&f=false');
    expect(buildQuery(undefined)).toBe('');
  });
});

describe('HttpClient', () => {
  it('sends GET without a CSRF token and parses JSON', async () => {
    const fetchFn = vi.fn().mockResolvedValue(json(200, { ok: true }));
    const client = new HttpClient(fetchFn);
    await expect(client.get('/api/x', { a: 1 })).resolves.toEqual({ ok: true });
    expect(fetchFn).toHaveBeenCalledTimes(1);
    expect(fetchFn.mock.calls[0][0]).toBe('/api/x?a=1');
  });

  it('fetches the CSRF token once and attaches it to mutations', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce(csrf('t1'))
      .mockImplementation(async () => json(200, {}));
    const client = new HttpClient(fetchFn);
    await client.post('/api/a', { n: 1 });
    await client.put('/api/b', { n: 2 });
    expect(fetchFn.mock.calls.map((c) => c[0])).toEqual(['/api/auth/csrf', '/api/a', '/api/b']);
    expect(fetchFn.mock.calls[1][1].headers['X-CSRF-TOKEN']).toBe('t1');
    expect(fetchFn.mock.calls[1][1].body).toBe('{"n":1}');
  });

  it('refreshes the token and retries once on CSRF_INVALID', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce(csrf('old'))
      .mockResolvedValueOnce(json(403, { code: 'CSRF_INVALID', message: 'bad token' }))
      .mockResolvedValueOnce(csrf('new'))
      .mockResolvedValueOnce(json(200, { done: true }));
    const client = new HttpClient(fetchFn);
    await expect(client.post('/api/a')).resolves.toEqual({ done: true });
    expect(fetchFn.mock.calls[3][1].headers['X-CSRF-TOKEN']).toBe('new');
  });

  it('turns error bodies into ApiError and notifies 401 listeners', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValue(json(401, { code: 'UNAUTHENTICATED', message: 'Sign in', requestId: 'r1' }));
    const client = new HttpClient(fetchFn);
    const listener = vi.fn();
    client.onUnauthorized(listener);
    const error = await client.get('/api/x').catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 401, code: 'UNAUTHENTICATED', requestId: 'r1' });
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('does not treat a failed login as an expired session', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce(csrf('t'))
      .mockImplementation(async () => json(401, { code: 'INVALID_CREDENTIALS', message: 'no' }));
    const client = new HttpClient(fetchFn);
    const listener = vi.fn();
    client.onUnauthorized(listener);
    await expect(client.postForm('/api/auth/login', { username: 'a', password: 'b' })).rejects.toThrow('no');
    expect(listener).not.toHaveBeenCalled();
    expect(String(fetchFn.mock.calls[1][1].body)).toBe('username=a&password=b');
  });

  it('handles 204 and non-JSON errors', async () => {
    const fetchFn = vi
      .fn()
      .mockResolvedValueOnce(csrf('t'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('oops', { status: 502, statusText: 'Bad Gateway' }));
    const client = new HttpClient(fetchFn);
    await expect(client.post('/api/logout')).resolves.toBeUndefined();
    await expect(client.get('/api/x')).rejects.toMatchObject({ status: 502, code: 'HTTP_502' });
  });

  it('formats field errors in messages', () => {
    const e = new ApiError(400, 'VALIDATION_FAILED', 'Request validation failed.', [
      { field: 'note', message: 'too long' },
    ]);
    expect(errorMessage(e)).toBe('Request validation failed. (note: too long)');
  });
});
