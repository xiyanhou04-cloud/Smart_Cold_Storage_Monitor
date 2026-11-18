import type { CsrfToken } from './types';

/** Error body shared by every backend error response ({@code web/ApiError.java}). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly fieldErrors: { field: string; message: string }[] = [],
    readonly requestId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export type QueryValue = string | number | boolean | undefined | null | readonly string[];
export type Query = Record<string, QueryValue>;

interface RequestOptions {
  query?: Query;
  /** JSON body. */
  json?: unknown;
  /** Form body (used only by Spring Security's login endpoint). */
  form?: Record<string, string>;
}

type UnauthorizedListener = () => void;

/** Builds `?a=1&b=x,y`, dropping empty values. Arrays are comma-joined, which every list parameter accepts. */
export function buildQuery(query: Query | undefined): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      if (value.length > 0) params.set(key, value.join(','));
    } else {
      params.set(key, String(value));
    }
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/**
 * The single gateway between the UI and the Spring Boot API (a Facade over fetch).
 *
 * - Keeps the session-bound CSRF token and attaches it to every mutating request; refreshes it once and retries
 *   when the server answers 403 CSRF_INVALID (the token rotates on login and when a session expires).
 * - Turns every non-2xx response into an {@link ApiError}.
 * - Notifies subscribers on 401 so the auth layer can return to the sign-in page (Observer).
 *
 * The fetch function is injected so tests can run the client against a fake server.
 */
export class HttpClient {
  private csrf: CsrfToken | null = null;
  private readonly unauthorizedListeners = new Set<UnauthorizedListener>();

  constructor(private readonly fetchFn: typeof fetch = (...args) => fetch(...args)) {}

  onUnauthorized(listener: UnauthorizedListener): () => void {
    this.unauthorizedListeners.add(listener);
    return () => this.unauthorizedListeners.delete(listener);
  }

  /** Forgets the cached CSRF token; the next mutation fetches a fresh one. Call after login and logout. */
  resetCsrf(): void {
    this.csrf = null;
  }

  get<T>(path: string, query?: Query): Promise<T> {
    return this.request<T>('GET', path, { query });
  }

  post<T>(path: string, json?: unknown): Promise<T> {
    return this.request<T>('POST', path, { json });
  }

  put<T>(path: string, json: unknown): Promise<T> {
    return this.request<T>('PUT', path, { json });
  }

  postForm<T>(path: string, form: Record<string, string>): Promise<T> {
    return this.request<T>('POST', path, { form });
  }

  async request<T>(method: string, path: string, options: RequestOptions = {}, retried = false): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    let body: BodyInit | undefined;
    if (options.json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(options.json);
    } else if (options.form) {
      body = new URLSearchParams(options.form);
    }
    if (MUTATING.has(method)) {
      const csrf = await this.csrfToken();
      headers[csrf.headerName] = csrf.token;
    }

    const response = await this.fetchFn(path + buildQuery(options.query), {
      method,
      headers,
      body,
      credentials: 'same-origin',
    });

    if (response.ok) {
      if (response.status === 204) return undefined as T;
      const text = await response.text();
      return (text ? JSON.parse(text) : undefined) as T;
    }

    const error = await toApiError(response);
    if (error.status === 403 && error.code === 'CSRF_INVALID' && !retried) {
      this.resetCsrf();
      return this.request<T>(method, path, options, true);
    }
    if (error.status === 401 && error.code !== 'INVALID_CREDENTIALS') {
      this.unauthorizedListeners.forEach((listener) => listener());
    }
    throw error;
  }

  private async csrfToken(): Promise<CsrfToken> {
    if (!this.csrf) {
      const response = await this.fetchFn('/api/auth/csrf', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
      });
      if (!response.ok) throw await toApiError(response);
      this.csrf = (await response.json()) as CsrfToken;
    }
    return this.csrf;
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = await response.json();
    return new ApiError(
      response.status,
      body.code ?? `HTTP_${response.status}`,
      body.message ?? response.statusText,
      body.fieldErrors ?? [],
      body.requestId,
    );
  } catch {
    return new ApiError(response.status, `HTTP_${response.status}`, response.statusText || 'Request failed');
  }
}

/** Human-readable text for an error caught from the client. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const fields = error.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ');
    return fields ? `${error.message} (${fields})` : error.message;
  }
  if (error instanceof Error) return error.message;
  return String(error);
}
