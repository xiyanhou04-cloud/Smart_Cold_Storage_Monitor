import { HttpClient } from './http';
import type {
  AcknowledgmentResult,
  Alert,
  AlertKind,
  AlertState,
  AuditEvent,
  Credential,
  CurrentUser,
  DeviceThreshold,
  LatestByDevice,
  Page,
  ReadingsByDevice,
  Role,
  ThresholdLimits,
  ThresholdRequest,
  ThresholdRequestStatus,
  ThresholdReview,
  ThresholdSubmission,
  User,
} from './types';

/**
 * Typed API surface, one group per backend controller. Pages call these functions and never build URLs themselves,
 * so a backend path change touches only this file.
 */
export function createApi(http: HttpClient) {
  return {
    http,

    auth: {
      me: () => http.get<CurrentUser>('/api/auth/me'),
      async login(username: string, password: string): Promise<CurrentUser> {
        http.resetCsrf();
        const user = await http.postForm<CurrentUser>('/api/auth/login', { username, password });
        http.resetCsrf(); // Spring Security rotates the token on login.
        return user;
      },
      async logout(): Promise<void> {
        try {
          await http.post<void>('/api/auth/logout');
        } finally {
          http.resetCsrf();
        }
      },
    },

    readings: {
      query: (params: { deviceId?: string[]; metric?: string[]; window?: string; from?: string; to?: string }) =>
        http.get<ReadingsByDevice>('/api/readings/query', params),
      latest: (deviceId?: string) =>
        http.get<LatestByDevice>('/api/readings/latest', deviceId ? { deviceId } : undefined),
    },

    thresholds: {
      list: () => http.get<DeviceThreshold[]>('/api/settings/thresholds'),
      submit: (deviceId: string, limits: ThresholdLimits) =>
        http.put<ThresholdSubmission>(`/api/settings/thresholds/${encodeURIComponent(deviceId)}`, limits),
      requests: (params: { deviceId?: string; status?: ThresholdRequestStatus[]; page?: number; size?: number }) =>
        http.get<Page<ThresholdRequest>>('/api/settings/threshold-requests', params),
      approve: (id: number, note?: string) =>
        http.post<ThresholdReview>(`/api/settings/threshold-requests/${id}/approval`, note ? { note } : undefined),
      reject: (id: number, note?: string) =>
        http.post<ThresholdReview>(`/api/settings/threshold-requests/${id}/rejection`, note ? { note } : undefined),
      cancel: (id: number) => http.post<ThresholdReview>(`/api/settings/threshold-requests/${id}/cancellation`),
    },

    alerts: {
      search: (params: {
        deviceId?: string[];
        kind?: AlertKind[];
        state?: AlertState;
        acknowledged?: boolean;
        from?: string;
        to?: string;
        page?: number;
        size?: number;
      }) => http.get<Page<Alert>>('/api/alerts', params),
      acknowledge: (id: number, note?: string) =>
        http.post<AcknowledgmentResult>(`/api/alerts/${id}/acknowledgment`, note ? { note } : undefined),
    },

    users: {
      list: () => http.get<User[]>('/api/users'),
      create: (username: string, role: Role, password?: string) =>
        http.post<Credential>('/api/users', { username, role, password: password || undefined }),
      resetPassword: (id: number, newPassword?: string) =>
        http.post<Credential>(`/api/users/${id}/password-reset`, newPassword ? { newPassword } : undefined),
      deactivate: (id: number) => http.post<User>(`/api/users/${id}/deactivation`),
    },

    audit: {
      search: (params: {
        from?: string;
        to?: string;
        actor?: string;
        eventType?: string[];
        result?: string[];
        page?: number;
        size?: number;
      }) => http.get<Page<AuditEvent>>('/api/audit-events', params),
    },
  };
}

export type Api = ReturnType<typeof createApi>;
