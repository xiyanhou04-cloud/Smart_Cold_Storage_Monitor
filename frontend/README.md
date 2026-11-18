# Frontend: React + TypeScript dashboard

The operator and administrator UI for Smart Cold Storage Monitor. It talks only to the Spring Boot API described in the
root README; it adds no backend endpoints.

## Setup and run

Prerequisites: Node.js 20 or newer.

```bash
cd frontend
npm install

# Against the real backend (start it first on 127.0.0.1:8080, see the root README)
npm run dev            # http://localhost:5173, /api is proxied to the backend

# Without a backend: in-memory mock API with seven demo zones
npm run dev:mock       # sign in as admin / 12345678 or olivia / operator-pass

npm test               # unit + integration tests (Vitest)
npm run test:coverage  # with a coverage report
npm run format         # Prettier, 120-column lines like the Java code
npm run build          # type-check and build into frontend/dist
npm run build:backend  # build into src/main/resources/static so Spring Boot serves it at http://127.0.0.1:8080/
```

`BACKEND_URL` overrides the proxy target (default `http://127.0.0.1:8080`). The app uses hash routes (`/#/alerts`),
so Spring Boot can serve the built files as plain static resources with no fallback route; `SecurityConfig` already
permits every non-`/api` path.

## Feature status

| Functional requirement               | Status                 | Where to find it                                                                                                                                                                                            |
| ------------------------------------ | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| FR-01 Login and logout               | Complete               | `pages/LoginPage.tsx`, `auth/AuthContext.tsx`; CSRF handling in `api/http.ts`; a 401 on any later call returns to sign-in with "session has ended"                                                          |
| FR-01 Account management (admin)     | Complete               | `pages/UsersPage.tsx`: create (generated password shown once), reset password, deactivate operators                                                                                                         |
| FR-03 Dashboard                      | Complete (frontend)    | `pages/DashboardPage.tsx`: 2-second polling of `GET /api/readings/query`, one card per zone with latest values against limits, trend charts with the allowed band (5 min to 6 h), connection-lost indicator |
| FR-03 Stale-input indication         | Complete (client-side) | `domain/status.ts`: a zone with no reading for 15 s (three missed 5-second readings) shows "Stale data"; none in 2 min shows "No data". Will switch to `GET /api/readings/latest` once the backend adds it  |
| FR-04 Threshold settings             | Complete               | `pages/ThresholdsPage.tsx`: admins apply limits directly; operators submit requests; admins approve or reject with a note; requesters cancel their own                                                      |
| FR-06 Alert acknowledgment           | Complete               | `pages/AlertsPage.tsx`: acknowledge with an optional note (500 chars)                                                                                                                                       |
| FR-08 Alert history and audit review | Complete               | `pages/AlertsPage.tsx` (state, acknowledgment, zone filters; paged), `pages/AuditPage.tsx` (time range, actor, event type, result; paged; admin only)                                                       |
| FR-05 Risk prediction display        | Not started            | Waits on the backend risk model; `Alert.risk` is already typed, and risk alerts appear in the alert list in the same format                                                                                 |

## Design patterns and principles

| Pattern / principle             | Where                                        | Why                                                                                                                        |
| ------------------------------- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Facade                          | `api/http.ts` `HttpClient`                   | One gateway for every request: CSRF token, JSON, error mapping. Pages never call `fetch` directly.                         |
| Adapter / typed API layer       | `api/endpoints.ts`, `api/types.ts`           | Maps backend paths and DTOs to typed functions; a path change touches one file.                                            |
| Dependency injection            | `api/ApiContext.tsx`, `HttpClient(fetchFn)`  | The app injects the real client; tests inject one backed by `FakeBackend`. No module singletons.                           |
| Observer                        | `HttpClient.onUnauthorized` → `AuthProvider` | Any 401 signs the UI out without each page handling it.                                                                    |
| Custom hook (reusable behavior) | `hooks/usePolling.ts`                        | One polling implementation (no overlapping requests, keeps last data on failure) for the dashboard, alerts and thresholds. |
| Separation of logic and view    | `domain/status.ts`, `domain/format.ts`       | Status, freshness and range rules are pure functions, unit tested without rendering.                                       |
| Guard / route protection        | `auth/guards.tsx`                            | Admin-only pages are hidden from the nav and redirected for operators; the backend still enforces roles.                   |

## Testing

- Unit tests: `domain/status.test.ts`, `domain/format.test.ts`, `api/http.test.ts` (CSRF fetch, retry on `CSRF_INVALID`, 401 observer, error mapping), `hooks/usePolling.test.tsx`, `pages/validateLimits.test.ts`.
- Integration tests: `test/app.integration.test.tsx` runs the real app, API layer and HTTP client against `mock/fakeBackend.ts`, which enforces the backend's CSRF, role, and status-code rules: sign-in and dashboard states, wrong password, session expiry, operator restrictions, sign-out, alert acknowledgment with audit event, alert filtering, operator threshold request then admin approval, admin direct change with validation, user creation with generated password, audit filtering.
- 42 tests, all passing (`npm test`).
- Manually checked against the real Spring Boot backend (default profile, built-in simulator): sign-in, live zone cards and charts, alert acknowledgment, admin threshold change, and the matching `ALERT_ACKNOWLEDGED` / `THRESHOLD_CHANGED` audit events.
- If `./mvnw` says "permission denied" (the execute bit is lost when the repo is downloaded as a zip), run `chmod +x mvnw` or use `sh mvnw`.

## Known issues

- Stale detection uses the browser clock against reading timestamps; it assumes the browser and server clocks agree (true for the localhost demo).
- The production bundle is about 600 kB (mostly Recharts); code splitting is not done yet.
