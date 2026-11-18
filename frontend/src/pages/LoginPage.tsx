import { useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { errorMessage } from '../api/http';
import { useAuth } from '../auth/AuthContext';

/**
 * Sign-in page (FR-01).
 *
 * Posts the form fields `username` and `password` to `POST /api/auth/login` through `AuthProvider`. The backend
 * answers every failure with the same `INVALID_CREDENTIALS` message, so the page cannot (and does not) say whether
 * the username exists. After a successful sign-in it returns to the page the user originally asked for.
 */
export function LoginPage() {
  const { user, login, sessionEnded } = useAuth();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  if (user) {
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await login(username.trim(), password);
    } catch (err) {
      setError(errorMessage(err));
      setPassword('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <div className="brand login-brand">
          <span className="brand-mark" aria-hidden="true">
            ❄
          </span>
          <span>Cold Storage Monitor</span>
        </div>
        <p className="muted">Sign in to view zone conditions and alerts.</p>
        {sessionEnded && !error && (
          <div className="notice notice-info">Your session has ended. Please sign in again.</div>
        )}
        {error && (
          <div className="notice notice-danger" role="alert">
            {error}
          </div>
        )}
        <label>
          Username
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            required
            autoFocus
          />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <button className="btn btn-primary" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
