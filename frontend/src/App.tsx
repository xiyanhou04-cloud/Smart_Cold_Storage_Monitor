import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ApiProvider } from './api/ApiContext';
import type { Api } from './api/endpoints';
import { AuthProvider } from './auth/AuthContext';
import { RequireAuth } from './auth/guards';
import { Layout } from './components/Layout';
import { AlertsPage } from './pages/AlertsPage';
import { AuditPage } from './pages/AuditPage';
import { DashboardPage } from './pages/DashboardPage';
import { LoginPage } from './pages/LoginPage';
import { ThresholdsPage } from './pages/ThresholdsPage';
import { UsersPage } from './pages/UsersPage';

/** Every page and who may open it. `/users` and `/audit` need ADMIN, matching `SecurityConfig`. */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="alerts" element={<AlertsPage />} />
        <Route path="thresholds" element={<ThresholdsPage />} />
        <Route
          path="users"
          element={
            <RequireAuth role="ADMIN">
              <UsersPage />
            </RequireAuth>
          }
        />
        <Route
          path="audit"
          element={
            <RequireAuth role="ADMIN">
              <AuditPage />
            </RequireAuth>
          }
        />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

/**
 * Hash routing (`/#/alerts`) lets Spring Boot serve the built files as plain static resources: every page is
 * index.html, so no server-side fallback route is needed.
 */
export function App({ api }: { api: Api }) {
  return (
    <ApiProvider api={api}>
      <AuthProvider>
        <HashRouter>
          <AppRoutes />
        </HashRouter>
      </AuthProvider>
    </ApiProvider>
  );
}
