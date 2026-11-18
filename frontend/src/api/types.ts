/**
 * TypeScript mirrors of the backend DTOs in {@code controller/dto}. Instants arrive as ISO 8601 UTC strings.
 * Keep these in step with the Java records; the UI depends only on these types, never on raw JSON.
 */

export type Role = 'OPERATOR' | 'ADMIN';

export interface CurrentUser {
  username: string;
  role: Role;
}

export interface CsrfToken {
  headerName: string;
  parameterName: string;
  token: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

// ---- Readings (FR-03) ----

export interface ReadingPoint {
  timestamp: string;
  value: number;
}

export interface DeviceReadings {
  temperatureC?: ReadingPoint[];
  humidityPct?: ReadingPoint[];
}

/** Response of GET /api/readings/query: keyed by device id, in id order. */
export type ReadingsByDevice = Record<string, DeviceReadings>;

// ---- Thresholds (FR-04) ----

export type ThresholdRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'SUPERSEDED';

export interface ThresholdLimits {
  temperatureLowerC: number;
  temperatureUpperC: number;
  humidityLowerPct: number;
  humidityUpperPct: number;
}

export interface ThresholdRequest extends ThresholdLimits {
  id: number;
  deviceId: string;
  status: ThresholdRequestStatus;
  baseThresholdId: number;
  requestedBy: string;
  requestedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  appliedThresholdId: number | null;
}

export interface DeviceThreshold {
  deviceId: string;
  deviceName: string;
  locationCode: string | null;
  enabled: boolean;
  sessionId: number | null;
  thresholdId: number | null;
  temperatureLowerC: number | null;
  temperatureUpperC: number | null;
  humidityLowerPct: number | null;
  humidityUpperPct: number | null;
  effectiveFrom: string | null;
  source: 'DEFAULT' | 'CUSTOM' | null;
  changedBy: string | null;
  pendingRequest: ThresholdRequest | null;
}

export type SubmissionOutcome = 'APPLIED' | 'UNCHANGED' | 'PENDING_APPROVAL';

export interface ThresholdSubmission {
  outcome: SubmissionOutcome;
  threshold: DeviceThreshold;
  request: ThresholdRequest | null;
}

export interface ThresholdReview {
  request: ThresholdRequest;
  threshold: DeviceThreshold;
}

// ---- Alerts (FR-04, FR-06, FR-08) ----

export type AlertKind = 'THRESHOLD' | 'RISK';
export type AlertState = 'ACTIVE' | 'RESOLVED';
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH';
export type Metric = 'TEMPERATURE' | 'HUMIDITY';

export interface Alert {
  id: number;
  deviceId: string;
  sessionId: number;
  kind: AlertKind;
  severity: Severity;
  source: string;
  description: string | null;
  state: AlertState;
  openedAt: string;
  lastSeenAt: string;
  resolvedAt: string | null;
  resolveReason: string | null;
  threshold?: {
    metric: Metric;
    direction: 'ABOVE' | 'BELOW';
    unit: string;
    limit: number;
    openingValue: number;
    lastValue: number;
    peakValue: number;
    thresholdId: number;
  };
  risk?: { modelOrRuleVersion: string; qualityFlag: string };
  acknowledgment: { userId: number; username: string; at: string; note: string | null } | null;
}

export interface AcknowledgmentResult {
  outcome: 'ACKNOWLEDGED' | 'ALREADY_ACKNOWLEDGED';
  alert: Alert;
}

// ---- Users and audit (FR-01, FR-07, FR-08) ----

export interface User {
  id: number;
  username: string;
  role: Role;
  enabled: boolean;
  createdAt: string;
}

export interface Credential extends User {
  /** Present only when the server generated the password; shown once. */
  generatedPassword?: string;
}

export type AuditResult = 'SUCCESS' | 'FAILURE' | 'DENIED';

export const AUDIT_EVENT_TYPES = [
  'LOGIN_SUCCESS',
  'LOGIN_FAILURE',
  'LOGOUT',
  'ACCESS_DENIED',
  'USER_CREATED',
  'PASSWORD_RESET',
  'USER_DEACTIVATED',
  'STORAGE_SESSION_STARTED',
  'THRESHOLD_CHANGED',
  'THRESHOLD_CHANGE_REQUESTED',
  'THRESHOLD_CHANGE_APPROVED',
  'THRESHOLD_CHANGE_REJECTED',
  'THRESHOLD_CHANGE_CANCELLED',
  'ALERT_ACKNOWLEDGED',
] as const;
export type AuditEventType = (typeof AUDIT_EVENT_TYPES)[number];

export interface AuditEvent {
  id: number;
  occurredAt: string;
  eventType: AuditEventType;
  result: string;
  actorId: number | null;
  actorUsername: string | null;
  attemptedUsername: string | null;
  targetType: string | null;
  targetId: string | null;
  details: string | null;
}
