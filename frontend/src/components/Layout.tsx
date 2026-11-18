import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

const LINKS: { to: string; label: string; end?: boolean }[] = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/alerts', label: 'Alerts' },
  { to: '/thresholds', label: 'Thresholds' },
];
const ADMIN_LINKS = [
  { to: '/users', label: 'Users' },
  { to: '/audit', label: 'Audit log' },
];

/**
 * Page frame for signed-in users: top bar with navigation, the signed-in user and role, and sign-out.
 * Administrator-only links (Users, Audit log) are shown only to ADMIN; the routes are also guarded, and the backend
 * enforces the role on every call regardless.
 */
export function Layout() {
  const { user, logout } = useAuth();
  const links: typeof LINKS = user?.role === 'ADMIN' ? [...LINKS, ...ADMIN_LINKS] : LINKS;
  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            ❄
          </span>
          <span>Cold Storage Monitor</span>
        </div>
        <nav className="nav" aria-label="Main">
          {links.map((l) => (
            <NavLink key={l.to} to={l.to} end={l.end} className={({ isActive }) => (isActive ? 'active' : undefined)}>
              {l.label}
            </NavLink>
          ))}
        </nav>
        <div className="user-box">
          <span className="user-name">{user?.username}</span>
          <span className={`role-chip role-${user?.role?.toLowerCase()}`}>{user?.role}</span>
          <button className="btn btn-ghost btn-sm" onClick={() => void logout()}>
            Sign out
          </button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
