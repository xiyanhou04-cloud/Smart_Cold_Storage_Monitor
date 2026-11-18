import { useState, type FormEvent } from 'react';
import { useApi } from '../api/ApiContext';
import { errorMessage } from '../api/http';
import type { Credential, Role, User } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Dialog } from '../components/Dialog';
import { Badge, EmptyRow, ErrorNotice, Notice, PageHeader } from '../components/ui';
import { formatDateTime } from '../domain/format';
import { usePolling } from '../hooks/usePolling';

/**
 * Account management for administrators (FR-01).
 *
 * Uses `/api/users`: create an OPERATOR or ADMIN account, reset a password, deactivate an operator. When no password
 * is typed the backend generates one and returns it only in that response, so the page shows it once and never
 * again. The backend refuses resetting another administrator's password and deactivating administrators; the
 * buttons are disabled or hidden in those cases so the user is not offered an action that will fail.
 */
export function UsersPage() {
  const api = useApi();
  const { user: me } = useAuth();
  const users = usePolling(() => api.users.list(), 0, [api]);
  const [creating, setCreating] = useState(false);
  const [resetting, setResetting] = useState<User>();
  const [credential, setCredential] = useState<{ title: string; value: Credential }>();
  const [message, setMessage] = useState<{ tone: 'ok' | 'danger'; text: string }>();

  const deactivate = async (u: User) => {
    if (!window.confirm(`Deactivate ${u.username}? Their sessions end immediately and they can no longer sign in.`))
      return;
    try {
      await api.users.deactivate(u.id);
      setMessage({ tone: 'ok', text: `${u.username} deactivated.` });
      users.refresh();
    } catch (err) {
      setMessage({ tone: 'danger', text: errorMessage(err) });
    }
  };

  const showCredential = (title: string, value: Credential) => {
    users.refresh();
    if (value.generatedPassword) setCredential({ title, value });
    else setMessage({ tone: 'ok', text: title });
  };

  return (
    <>
      <PageHeader
        title="Users"
        subtitle="Create operator or administrator accounts, reset passwords, and deactivate operators."
        actions={
          <button className="btn btn-primary" onClick={() => setCreating(true)}>
            New user
          </button>
        }
      />
      {message && (
        <Notice tone={message.tone} onClose={() => setMessage(undefined)}>
          {message.text}
        </Notice>
      )}
      <ErrorNotice error={users.error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Username</th>
              <th>Role</th>
              <th>Status</th>
              <th>Created</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {users.data?.length === 0 && <EmptyRow colSpan={5}>No users.</EmptyRow>}
            {users.data?.map((u) => {
              const isOtherAdmin = u.role === 'ADMIN' && u.username !== me?.username;
              return (
                <tr key={u.id}>
                  <td>
                    <strong>{u.username}</strong>{' '}
                    {u.username === me?.username && <span className="muted small">(you)</span>}
                  </td>
                  <td>
                    <span className={`role-chip role-${u.role.toLowerCase()}`}>{u.role}</span>
                  </td>
                  <td>{u.enabled ? <Badge tone="ok">Active</Badge> : <Badge tone="muted">Deactivated</Badge>}</td>
                  <td>{formatDateTime(u.createdAt)}</td>
                  <td className="actions">
                    <button
                      className="btn btn-sm"
                      disabled={!u.enabled || isOtherAdmin}
                      title={isOtherAdmin ? "Another administrator's password cannot be reset" : undefined}
                      onClick={() => setResetting(u)}
                    >
                      Reset password
                    </button>
                    {u.role === 'OPERATOR' && u.enabled && (
                      <button className="btn btn-sm btn-danger" onClick={() => void deactivate(u)}>
                        Deactivate
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {creating && (
        <CreateUserDialog
          onClose={() => setCreating(false)}
          onDone={(c) => {
            setCreating(false);
            showCredential(`Account ${c.username} created.`, c);
          }}
        />
      )}
      {resetting && (
        <ResetPasswordDialog
          user={resetting}
          onClose={() => setResetting(undefined)}
          onDone={(c) => {
            setResetting(undefined);
            showCredential(`Password for ${c.username} reset.`, c);
          }}
        />
      )}
      {credential && (
        <Dialog title={credential.title} open onClose={() => setCredential(undefined)}>
          <p>The generated password is shown only once. Give it to {credential.value.username} now.</p>
          <div className="secret" data-testid="generated-password">
            {credential.value.generatedPassword}
          </div>
          <div className="form-actions">
            <button
              className="btn"
              onClick={() => void navigator.clipboard?.writeText(credential.value.generatedPassword ?? '')}
            >
              Copy
            </button>
            <button className="btn btn-primary" onClick={() => setCredential(undefined)}>
              Done
            </button>
          </div>
        </Dialog>
      )}
    </>
  );
}

function CreateUserDialog({ onClose, onDone }: { onClose: () => void; onDone: (c: Credential) => void }) {
  const api = useApi();
  const [username, setUsername] = useState('');
  const [role, setRole] = useState<Role>('OPERATOR');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      onDone(await api.users.create(username.trim(), role, password));
    } catch (err) {
      setError(errorMessage(err));
    }
  };
  return (
    <Dialog title="New user" open onClose={onClose}>
      <form className="form" onSubmit={submit}>
        {error && <Notice tone="danger">{error}</Notice>}
        <label>
          Username
          <input value={username} onChange={(e) => setUsername(e.target.value)} maxLength={64} required />
        </label>
        <label>
          Role
          <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
            <option value="OPERATOR">Operator</option>
            <option value="ADMIN">Administrator</option>
          </select>
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
          />
          <span className="hint">Leave empty to generate one.</span>
        </label>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary">
            Create
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function ResetPasswordDialog({
  user,
  onClose,
  onDone,
}: {
  user: User;
  onClose: () => void;
  onDone: (c: Credential) => void;
}) {
  const api = useApi();
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      onDone(await api.users.resetPassword(user.id, password || undefined));
    } catch (err) {
      setError(errorMessage(err));
    }
  };
  return (
    <Dialog title={`Reset password · ${user.username}`} open onClose={onClose}>
      <form className="form" onSubmit={submit}>
        {error && <Notice tone="danger">{error}</Notice>}
        <p className="muted">The user's other sessions end immediately.</p>
        <label>
          New password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
          />
          <span className="hint">Leave empty to generate one.</span>
        </label>
        <div className="form-actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary">
            Reset password
          </button>
        </div>
      </form>
    </Dialog>
  );
}
