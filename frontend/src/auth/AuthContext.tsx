import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useApi } from '../api/ApiContext';
import type { CurrentUser } from '../api/types';

interface AuthState {
  /** undefined while the initial `/api/auth/me` check runs; null when signed out. */
  user: CurrentUser | null | undefined;
  login(username: string, password: string): Promise<void>;
  logout(): Promise<void>;
  /** Set when the server ended the session (401 on a later call), so the sign-in page can explain why. */
  sessionEnded: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Signed-in user state for the whole app (FR-01).
 *
 * On start it asks `GET /api/auth/me` whether the browser already has a session. Sign-in and sign-out go through the
 * API layer, which handles the CSRF token. Any later 401 (session timed out, password reset or account deactivated
 * by an administrator) signs the UI out and sets `sessionEnded` so the sign-in page can explain why.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const [user, setUser] = useState<CurrentUser | null | undefined>(undefined);
  const [sessionEnded, setSessionEnded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.auth
      .me()
      .then((me) => !cancelled && setUser(me))
      .catch(() => !cancelled && setUser(null));
    return () => {
      cancelled = true;
    };
  }, [api]);

  // Any 401 from a later request (expired session, password reset by an admin, deactivation) signs the UI out.
  useEffect(
    () =>
      api.http.onUnauthorized(() => {
        setUser((current) => {
          if (current) setSessionEnded(true);
          return null;
        });
      }),
    [api],
  );

  const login = useCallback(
    async (username: string, password: string) => {
      const me = await api.auth.login(username, password);
      setSessionEnded(false);
      setUser(me);
    },
    [api],
  );

  const logout = useCallback(async () => {
    try {
      await api.auth.logout();
    } finally {
      setUser(null);
    }
  }, [api]);

  const value = useMemo(() => ({ user, login, logout, sessionEnded }), [user, login, logout, sessionEnded]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error('useAuth must be used inside <AuthProvider>');
  return auth;
}

export function useIsAdmin(): boolean {
  return useAuth().user?.role === 'ADMIN';
}
