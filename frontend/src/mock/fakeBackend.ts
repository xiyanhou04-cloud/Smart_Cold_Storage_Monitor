import type {
  Alert,
  AuditEvent,
  DeviceThreshold,
  LatestByDevice,
  ReadingPoint,
  ReadingsByDevice,
  RiskSummary,
  Role,
  ThresholdLimits,
  ThresholdRequest,
  User,
} from '../api/types';

/**
 * In-memory stand-in for the Spring Boot API, implementing the subset of its contract the frontend uses (paths,
 * status codes, error codes, CSRF and role rules). Integration tests pass `backend.fetch` to the real HttpClient, so
 * the pages, API layer, and HTTP handling all run unchanged. `npm run dev:mock` also serves it through Vite for UI
 * work without a JDK 21 backend.
 *
 * Seven zones follow the seven-zone demo: ESP32-SIM-001 (RAW-MEAT) drifts above its upper limit, and ESP32-SIM-006
 * stopped reporting 40 s ago, so the excursion and stale-data states are always visible.
 */

const ZONES: [string, string, ThresholdLimits][] = [
  [
    'ESP32-SIM-001',
    'RAW-MEAT',
    { temperatureLowerC: 0, temperatureUpperC: 4, humidityLowerPct: 55, humidityUpperPct: 80 },
  ],
  [
    'ESP32-SIM-002',
    'VEG-COLD',
    { temperatureLowerC: 0, temperatureUpperC: 2.2, humidityLowerPct: 95, humidityUpperPct: 98 },
  ],
  [
    'ESP32-SIM-003',
    'VEG-COOL',
    { temperatureLowerC: 10, temperatureUpperC: 12.2, humidityLowerPct: 90, humidityUpperPct: 95 },
  ],
  ['ESP32-SIM-004', 'EGGS', { temperatureLowerC: 0, temperatureUpperC: 4, humidityLowerPct: 55, humidityUpperPct: 80 }],
  [
    'ESP32-SIM-005',
    'FROZEN',
    { temperatureLowerC: -25, temperatureUpperC: -18, humidityLowerPct: 35, humidityUpperPct: 80 },
  ],
  [
    'ESP32-SIM-006',
    'FRUIT-COLD-BERRY',
    { temperatureLowerC: -0.5, temperatureUpperC: 0.5, humidityLowerPct: 90, humidityUpperPct: 95 },
  ],
  [
    'ESP32-SIM-007',
    'FRUIT-TROPICAL',
    { temperatureLowerC: 13, temperatureUpperC: 14, humidityLowerPct: 90, humidityUpperPct: 95 },
  ],
];
const STEP_MS = 5000;
const STALE_DEVICE = 'ESP32-SIM-006';
const HOT_DEVICE = 'ESP32-SIM-001';

type Prediction = Omit<RiskSummary, 'predictedAt'>;
const MODEL_VERSION = 'random-forest-v1';
const complete = { historicalExposureIncomplete: false, currentGapReason: 'NONE', lastGapReason: 'NONE' } as const;
/**
 * Latest prediction per zone, covering each case the dashboard distinguishes: complete history (model), a gap that
 * ends at the current reading (rules fallback), a gap earlier in the session (model, possibly underestimated), and a
 * reading not scored yet (null).
 */
const PREDICTIONS: Record<string, Prediction | null> = {
  'ESP32-SIM-001': {
    riskLevel: 'HIGH',
    source: 'MODEL',
    modelOrRuleVersion: MODEL_VERSION,
    qualityFlag: 'OK',
    ...complete,
  },
  'ESP32-SIM-002': {
    riskLevel: 'LOW',
    source: 'MODEL',
    modelOrRuleVersion: MODEL_VERSION,
    qualityFlag: 'OK',
    ...complete,
  },
  'ESP32-SIM-003': {
    riskLevel: 'MEDIUM',
    source: 'RULES',
    modelOrRuleVersion: 'simulated-exposure-v1',
    qualityFlag: 'INCOMPLETE_HISTORY',
    historicalExposureIncomplete: true,
    currentGapReason: 'RUNNING_DATA_GAP',
    lastGapReason: 'RUNNING_DATA_GAP',
  },
  'ESP32-SIM-004': {
    riskLevel: 'LOW',
    source: 'MODEL',
    modelOrRuleVersion: MODEL_VERSION,
    qualityFlag: 'OK',
    historicalExposureIncomplete: true,
    currentGapReason: 'NONE',
    lastGapReason: 'ORDERLY_MONITORING_INTERRUPTION',
  },
  'ESP32-SIM-005': {
    riskLevel: 'LOW',
    source: 'MODEL',
    modelOrRuleVersion: MODEL_VERSION,
    qualityFlag: 'OK',
    ...complete,
  },
  'ESP32-SIM-006': {
    riskLevel: 'LOW',
    source: 'MODEL',
    modelOrRuleVersion: MODEL_VERSION,
    qualityFlag: 'OK',
    ...complete,
  },
  'ESP32-SIM-007': null,
};

interface StoredUser extends User {
  password: string;
}

interface Options {
  now?: () => number;
  /** Starts signed in as this user (tests). */
  signedInAs?: string;
}

export class FakeBackend {
  readonly now: () => number;
  users: StoredUser[] = [];
  thresholds = new Map<string, DeviceThreshold>();
  /** Latest prediction per device; tests may replace entries. */
  predictions: Record<string, Prediction | null> = structuredClone(PREDICTIONS);
  requests: ThresholdRequest[] = [];
  alerts: Alert[] = [];
  audit: AuditEvent[] = [];
  session: StoredUser | null = null;
  csrf = 'csrf-0';
  /** Every request received, for assertions. */
  log: { method: string; path: string; body?: string }[] = [];
  private seq = 100;

  constructor(options: Options = {}) {
    this.now = options.now ?? (() => Date.now());
    const created = new Date(this.now() - 86_400_000).toISOString();
    this.users = [
      { id: 1, username: 'admin', role: 'ADMIN', enabled: true, createdAt: created, password: '12345678' },
      { id: 2, username: 'olivia', role: 'OPERATOR', enabled: true, createdAt: created, password: 'operator-pass' },
    ];
    const started = new Date(this.now() - 3_600_000).toISOString();
    ZONES.forEach(([id, zone, limits], i) => {
      this.thresholds.set(id, {
        deviceId: id,
        deviceName: `${zone} sensor`,
        locationCode: zone,
        enabled: true,
        sessionId: i + 1,
        thresholdId: i + 1,
        ...limits,
        effectiveFrom: started,
        source: 'DEFAULT',
        changedBy: null,
        pendingRequest: null,
      });
    });
    const t = (minAgo: number) => new Date(this.now() - minAgo * 60_000).toISOString();
    this.alerts = [
      this.makeAlert(3, HOT_DEVICE, 'TEMPERATURE', 'ABOVE', 4, 4.6, 4.9, t(6), null),
      this.makeAlert(2, 'ESP32-SIM-003', 'HUMIDITY', 'BELOW', 90, 88.7, 87.9, t(40), t(31)),
      this.makeAlert(1, 'ESP32-SIM-005', 'TEMPERATURE', 'ABOVE', -18, -17.2, -16.8, t(90), t(82)),
    ];
    this.alerts[2].acknowledgment = {
      userId: 2,
      username: 'olivia',
      at: t(85),
      note: 'Door left open during loading.',
    };
    this.record('STORAGE_SESSION_STARTED', 'SUCCESS', null, 'STORAGE_SESSION', '1');
    if (options.signedInAs) this.session = this.users.find((u) => u.username === options.signedInAs) ?? null;
  }

  // ---------------------------------------------------------------- synthetic readings

  /** Deterministic value for a device at a time, so charts look like slow real drift. */
  valueAt(deviceId: string, metric: 'temperature' | 'humidity', time: number): number {
    const limits = this.thresholds.get(deviceId)!;
    const [lo, hi] =
      metric === 'temperature'
        ? [limits.temperatureLowerC!, limits.temperatureUpperC!]
        : [limits.humidityLowerPct!, limits.humidityUpperPct!];
    const mid = (lo + hi) / 2;
    const span = (hi - lo) / 2;
    const phase = Number(deviceId.slice(-1)) * 1.3 + (metric === 'humidity' ? 2 : 0);
    let v = mid + span * 0.55 * Math.sin(time / 90_000 + phase) + span * 0.08 * Math.sin(time / 7_000 + phase);
    if (deviceId === HOT_DEVICE && metric === 'temperature') v += span * 0.9 * Math.max(0, Math.sin(time / 240_000));
    return Math.round(v * 1000) / 1000;
  }

  readings(deviceIds: string[], from: number, to: number, metrics: string[]): ReadingsByDevice {
    const out: ReadingsByDevice = {};
    for (const id of deviceIds) {
      const last = id === STALE_DEVICE ? this.now() - 40_000 : to;
      const points = (metric: 'temperature' | 'humidity'): ReadingPoint[] => {
        const list: ReadingPoint[] = [];
        for (let t = Math.ceil(from / STEP_MS) * STEP_MS; t < Math.min(to, last); t += STEP_MS) {
          list.push({ timestamp: new Date(t).toISOString(), value: this.valueAt(id, metric, t) });
        }
        return list;
      };
      out[id] = {};
      if (metrics.includes('temperature')) out[id].temperatureC = points('temperature');
      if (metrics.includes('humidity')) out[id].humidityPct = points('humidity');
    }
    return out;
  }

  /** GET /api/readings/latest: newest reading per device with its prediction, keyed by device id. */
  latest(deviceIds: string[]): LatestByDevice {
    const out: LatestByDevice = {};
    deviceIds.forEach((id, i) => {
      const last = id === STALE_DEVICE ? this.now() - 40_000 : this.now();
      const measured = Math.floor((last - 1) / STEP_MS) * STEP_MS;
      const measuredAt = new Date(measured).toISOString();
      const prediction = this.predictions[id];
      out[id] = {
        reading: {
          id: Math.floor(measured / STEP_MS) * 10 + i,
          deviceId: id,
          sessionId: i + 1,
          measuredAt,
          receivedAt: new Date(measured + 40).toISOString(),
          temperatureC: this.valueAt(id, 'temperature', measured),
          humidityPct: this.valueAt(id, 'humidity', measured),
          thresholdId: i + 1,
        },
        stale: this.now() - measured > 30_000,
        prediction: prediction ? { ...prediction, predictedAt: new Date(measured + 120).toISOString() } : null,
      };
    });
    return out;
  }

  // ---------------------------------------------------------------- fetch

  fetch = async (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
    const url = new URL(String(input), 'http://localhost');
    const method = (init.method ?? 'GET').toUpperCase();
    const body =
      typeof init.body === 'string'
        ? init.body
        : init.body instanceof URLSearchParams
          ? init.body.toString()
          : undefined;
    this.log.push({ method, path: url.pathname + url.search, body });
    const headers = new Headers(init.headers);
    try {
      return this.route(method, url, headers, body);
    } catch (e) {
      if (e instanceof HttpFailure) return json(e.status, { code: e.code, message: e.message });
      throw e;
    }
  };

  private route(method: string, url: URL, headers: Headers, body: string | undefined): Response {
    const path = url.pathname;
    const q = url.searchParams;

    if (method === 'GET' && path === '/api/auth/csrf') {
      return json(200, { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: this.csrf });
    }
    if (method !== 'GET' && headers.get('X-CSRF-TOKEN') !== this.csrf) {
      fail(403, 'CSRF_INVALID', 'Missing or invalid CSRF token.');
    }
    if (method === 'POST' && path === '/api/auth/login') {
      const form = new URLSearchParams(body);
      const user = this.users.find((u) => u.username === form.get('username'));
      if (!user || !user.enabled || user.password !== form.get('password')) {
        this.record('LOGIN_FAILURE', 'FAILURE', null, null, null, form.get('username'));
        fail(401, 'INVALID_CREDENTIALS', 'Invalid username or password.');
      }
      this.session = user!;
      this.csrf = `csrf-${++this.seq}`;
      this.record('LOGIN_SUCCESS', 'SUCCESS', user!, null, null);
      return json(200, { username: user!.username, role: user!.role });
    }

    const me = this.session;
    if (!me) fail(401, 'UNAUTHENTICATED', 'Sign in required.');
    const admin = me!.role === 'ADMIN';
    const requireAdmin = () => {
      if (!admin) fail(403, 'FORBIDDEN', 'Administrator role required.');
    };
    const input = body ? JSON.parse(body) : {};
    let m: RegExpMatchArray | null;

    if (method === 'POST' && path === '/api/auth/logout') {
      this.record('LOGOUT', 'SUCCESS', me!, null, null);
      this.session = null;
      this.csrf = `csrf-${++this.seq}`;
      return new Response(null, { status: 204 });
    }
    if (method === 'GET' && path === '/api/auth/me') return json(200, { username: me!.username, role: me!.role });

    if (method === 'GET' && path === '/api/readings/latest') {
      const id = q.get('deviceId');
      // Like the backend, an unknown device simply has no entry.
      return json(200, this.latest(id ? [id].filter((d) => this.thresholds.has(d)) : [...this.thresholds.keys()]));
    }
    if (method === 'GET' && path === '/api/readings/query') {
      const ids = list(q, 'deviceId');
      const unknown = ids.find((id) => !this.thresholds.has(id));
      if (unknown) fail(400, 'VALIDATION_FAILED', `Unknown device ${unknown}.`);
      const to = q.get('to') ? Date.parse(q.get('to')!) : this.now();
      const from = q.get('from') ? Date.parse(q.get('from')!) : to - parseWindow(q.get('window') ?? '5m');
      const metrics = list(q, 'metric');
      return json(
        200,
        this.readings(
          ids.length ? ids : [...this.thresholds.keys()],
          from,
          to,
          metrics.length ? metrics : ['temperature', 'humidity'],
        ),
      );
    }

    if (method === 'GET' && path === '/api/settings/thresholds') return json(200, [...this.thresholds.values()]);
    if ((m = path.match(/^\/api\/settings\/thresholds\/(.+)$/)) && method === 'PUT') {
      const device = this.thresholds.get(decodeURIComponent(m[1]));
      if (!device) fail(404, 'NOT_FOUND', 'Unknown device.');
      const limits = input as ThresholdLimits;
      if (limits.temperatureLowerC >= limits.temperatureUpperC || limits.humidityLowerPct >= limits.humidityUpperPct) {
        fail(400, 'VALIDATION_FAILED', 'Each lower limit must be below its upper limit.');
      }
      if (admin) {
        Object.assign(device!, limits, {
          source: 'CUSTOM',
          changedBy: me!.username,
          thresholdId: device!.thresholdId! + 100,
          effectiveFrom: new Date(this.now()).toISOString(),
        });
        this.supersede(device!.deviceId, me!.username);
        this.record('THRESHOLD_CHANGED', 'SUCCESS', me!, 'DEVICE', device!.deviceId);
        return json(200, { outcome: 'APPLIED', threshold: device, request: null });
      }
      const pending = device!.pendingRequest;
      if (pending && pending.requestedBy !== me!.username)
        fail(409, 'THRESHOLD_REQUEST_PENDING', 'Another request is pending.');
      if (pending) pending.status = 'SUPERSEDED';
      const request: ThresholdRequest = {
        id: ++this.seq,
        deviceId: device!.deviceId,
        status: 'PENDING',
        ...limits,
        baseThresholdId: device!.thresholdId!,
        requestedBy: me!.username,
        requestedAt: new Date(this.now()).toISOString(),
        reviewedBy: null,
        reviewedAt: null,
        reviewNote: null,
        appliedThresholdId: null,
      };
      this.requests.unshift(request);
      device!.pendingRequest = request;
      this.record('THRESHOLD_CHANGE_REQUESTED', 'SUCCESS', me!, 'THRESHOLD_REQUEST', String(request.id));
      return json(202, { outcome: 'PENDING_APPROVAL', threshold: device, request });
    }
    if (method === 'GET' && path === '/api/settings/threshold-requests') {
      const statuses = list(q, 'status');
      const items = this.requests.filter(
        (r) => (admin || r.requestedBy === me!.username) && (!statuses.length || statuses.includes(r.status)),
      );
      return json(200, paged(items, q));
    }
    if (
      (m = path.match(/^\/api\/settings\/threshold-requests\/(\d+)\/(approval|rejection|cancellation)$/)) &&
      method === 'POST'
    ) {
      const request = this.requests.find((r) => r.id === Number(m![1]));
      if (!request) fail(404, 'NOT_FOUND', 'Unknown request.');
      if (m[2] !== 'cancellation') requireAdmin();
      if (m[2] === 'cancellation' && request!.requestedBy !== me!.username) fail(403, 'FORBIDDEN', 'Not your request.');
      if (request!.status !== 'PENDING') fail(409, 'THRESHOLD_REQUEST_NOT_PENDING', 'Request is not pending.');
      const device = this.thresholds.get(request!.deviceId)!;
      request!.status = m[2] === 'approval' ? 'APPROVED' : m[2] === 'rejection' ? 'REJECTED' : 'CANCELLED';
      request!.reviewedBy = me!.username;
      request!.reviewedAt = new Date(this.now()).toISOString();
      request!.reviewNote = input.note ?? null;
      if (request!.status === 'APPROVED') {
        const { temperatureLowerC, temperatureUpperC, humidityLowerPct, humidityUpperPct } = request!;
        Object.assign(device, {
          temperatureLowerC,
          temperatureUpperC,
          humidityLowerPct,
          humidityUpperPct,
          source: 'CUSTOM',
          changedBy: me!.username,
        });
      }
      device.pendingRequest = null;
      this.record(
        `THRESHOLD_CHANGE_${request!.status}` as AuditEvent['eventType'],
        'SUCCESS',
        me!,
        'THRESHOLD_REQUEST',
        String(request!.id),
      );
      return json(200, { request, threshold: device });
    }

    if (method === 'GET' && path === '/api/alerts') {
      const devices = list(q, 'deviceId');
      const ack = q.get('acknowledged');
      const items = this.alerts.filter(
        (a) =>
          (!q.get('state') || a.state === q.get('state')) &&
          (!devices.length || devices.includes(a.deviceId)) &&
          (ack === null || (ack === 'true') === Boolean(a.acknowledgment)),
      );
      return json(200, paged(items, q));
    }
    if ((m = path.match(/^\/api\/alerts\/(\d+)\/acknowledgment$/)) && method === 'POST') {
      const alert = this.alerts.find((a) => a.id === Number(m![1]));
      if (!alert) fail(404, 'NOT_FOUND', 'Unknown alert.');
      if (alert!.acknowledgment) return json(200, { outcome: 'ALREADY_ACKNOWLEDGED', alert });
      alert!.acknowledgment = {
        userId: me!.id,
        username: me!.username,
        at: new Date(this.now()).toISOString(),
        note: input.note ?? null,
      };
      this.record('ALERT_ACKNOWLEDGED', 'SUCCESS', me!, 'ALERT', String(alert!.id));
      return json(200, { outcome: 'ACKNOWLEDGED', alert });
    }

    if (path.startsWith('/api/users') || path.startsWith('/api/audit-events')) requireAdmin();
    if (method === 'GET' && path === '/api/users') return json(200, this.users.map(publicUser));
    if (method === 'POST' && path === '/api/users') {
      if (this.users.some((u) => u.username === input.username))
        fail(409, 'USERNAME_TAKEN', 'Username already exists.');
      const generated = input.password ? undefined : 'Gen-' + Math.random().toString(36).slice(2, 10);
      const user: StoredUser = {
        id: ++this.seq,
        username: input.username,
        role: input.role as Role,
        enabled: true,
        createdAt: new Date(this.now()).toISOString(),
        password: input.password ?? generated,
      };
      this.users.push(user);
      this.record('USER_CREATED', 'SUCCESS', me!, 'USER', String(user.id));
      return json(201, { ...publicUser(user), ...(generated ? { generatedPassword: generated } : {}) });
    }
    if ((m = path.match(/^\/api\/users\/(\d+)\/(password-reset|deactivation)$/)) && method === 'POST') {
      const user = this.users.find((u) => u.id === Number(m![1]));
      if (!user) fail(404, 'NOT_FOUND', 'Unknown user.');
      if (m[2] === 'deactivation') {
        if (user!.role === 'ADMIN') fail(403, 'OPERATION_NOT_PERMITTED', 'Administrators cannot be deactivated.');
        user!.enabled = false;
        this.record('USER_DEACTIVATED', 'SUCCESS', me!, 'USER', String(user!.id));
        return json(200, publicUser(user!));
      }
      const generated = input.newPassword ? undefined : 'Gen-' + Math.random().toString(36).slice(2, 10);
      user!.password = input.newPassword ?? generated;
      this.record('PASSWORD_RESET', 'SUCCESS', me!, 'USER', String(user!.id));
      return json(200, { ...publicUser(user!), ...(generated ? { generatedPassword: generated } : {}) });
    }
    if (method === 'GET' && path === '/api/audit-events') {
      const types = list(q, 'eventType');
      const actor = q.get('actor');
      const items = this.audit.filter(
        (e) =>
          (!types.length || types.includes(e.eventType)) &&
          (!actor || e.actorUsername === actor || e.attemptedUsername === actor),
      );
      return json(200, paged(items, q));
    }

    fail(404, 'NOT_FOUND', 'No resource at this path.');
    throw new Error('unreachable');
  }

  // ---------------------------------------------------------------- helpers

  private makeAlert(
    id: number,
    deviceId: string,
    metric: 'TEMPERATURE' | 'HUMIDITY',
    direction: 'ABOVE' | 'BELOW',
    limit: number,
    opening: number,
    peak: number,
    openedAt: string,
    resolvedAt: string | null,
  ): Alert {
    const unit = metric === 'TEMPERATURE' ? '°C' : '%RH';
    const name = metric === 'TEMPERATURE' ? 'Temperature' : 'Humidity';
    return {
      id,
      deviceId,
      sessionId: Number(deviceId.slice(-1)),
      kind: 'THRESHOLD',
      severity: 'HIGH',
      source: 'THRESHOLD',
      description: `${name} ${opening} ${unit} is ${direction.toLowerCase()} the ${direction === 'ABOVE' ? 'upper' : 'lower'} limit of ${limit} ${unit}.`,
      state: resolvedAt ? 'RESOLVED' : 'ACTIVE',
      openedAt,
      lastSeenAt: resolvedAt ?? new Date(this.now()).toISOString(),
      resolvedAt,
      resolveReason: resolvedAt ? 'RECOVERED' : null,
      threshold: {
        metric,
        direction,
        unit,
        limit,
        openingValue: opening,
        lastValue: peak,
        peakValue: peak,
        thresholdId: 1,
      },
      acknowledgment: null,
    };
  }

  private supersede(deviceId: string, by: string) {
    const device = this.thresholds.get(deviceId)!;
    if (device.pendingRequest) {
      device.pendingRequest.status = 'SUPERSEDED';
      device.pendingRequest.reviewedBy = by;
      device.pendingRequest = null;
    }
  }

  private record(
    eventType: AuditEvent['eventType'],
    result: string,
    actor: User | null,
    targetType: string | null,
    targetId: string | null,
    attempted: string | null = null,
  ) {
    this.audit.unshift({
      id: this.audit.length + 1,
      occurredAt: new Date(this.now()).toISOString(),
      eventType,
      result,
      actorId: actor?.id ?? null,
      actorUsername: actor?.username ?? null,
      attemptedUsername: attempted,
      targetType,
      targetId,
      details: null,
    });
  }
}

class HttpFailure extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

function fail(status: number, code: string, message: string): never {
  throw new HttpFailure(status, code, message);
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function list(q: URLSearchParams, key: string): string[] {
  return q
    .getAll(key)
    .flatMap((v) => v.split(','))
    .map((v) => v.trim())
    .filter(Boolean);
}

function paged<T>(items: T[], q: URLSearchParams) {
  const page = Number(q.get('page') ?? 0);
  const size = Math.min(Number(q.get('size') ?? 50), 200);
  return {
    items: items.slice(page * size, page * size + size),
    page,
    size,
    totalItems: items.length,
    totalPages: Math.ceil(items.length / size),
  };
}

function parseWindow(w: string): number {
  const m = w.match(/^(\d+)([smh])$/);
  if (!m) return 5 * 60_000;
  return Number(m[1]) * { s: 1000, m: 60_000, h: 3_600_000 }[m[2] as 's' | 'm' | 'h'];
}

function publicUser({ password: _password, ...user }: StoredUser): User {
  return user;
}
