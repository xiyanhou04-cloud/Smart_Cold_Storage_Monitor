import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { useAuth } from './AuthContext';

/**
 * Renders its children only for a signed-in user (optionally of one role); otherwise redirects to sign-in or to the
 * dashboard. This only keeps users away from pages they cannot use; the backend enforces roles on every request.
 */
export function RequireAuth({ role, children }: { role?: Role; children: ReactNode }) {
  const { user } = useAuth();
  const location = useLocation();
  if (user === undefined) return <div className="page-loading">Checking session…</div>;
  if (user === null) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (role && user.role !== role) return <Navigate to="/" replace />;
  return <>{children}</>;
}
