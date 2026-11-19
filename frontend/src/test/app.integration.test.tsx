import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../App';
import { createApi } from '../api/endpoints';
import { HttpClient } from '../api/http';
import { FakeBackend } from '../mock/fakeBackend';

/**
 * Key workflows end to end through the real App, API layer, and HttpClient, against the in-memory FakeBackend
 * (which enforces the backend's CSRF, role, and status-code rules).
 */
function start(backend: FakeBackend, hash = '#/') {
  window.location.hash = hash;
  return render(<App api={createApi(new HttpClient(backend.fetch))} />);
}

async function signIn(user: ReturnType<typeof userEvent.setup>, username: string, password: string) {
  await user.type(await screen.findByLabelText('Username'), username);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

beforeEach(() => {
  window.location.hash = '';
});

describe('sign-in and dashboard', () => {
  it('signs in and shows every zone with live, stale, and alert states', async () => {
    const backend = new FakeBackend();
    const user = userEvent.setup();
    start(backend);

    await signIn(user, 'admin', '12345678');

    const zones = await screen.findByRole('region', { name: 'Zones' });
    await waitFor(() => expect(within(zones).getAllByRole('button')).toHaveLength(7));
    expect(within(zones).getByRole('button', { name: /FRUIT-COLD-BERRY Stale data/ })).toBeInTheDocument();
    expect(within(zones).getByRole('button', { name: /RAW-MEAT Excursion/ })).toBeInTheDocument();
    expect(await screen.findByText(/Temperature 4.6 °C is above the upper limit/)).toBeInTheDocument();

    // The login used a CSRF token, and the token rotated afterwards (later mutations fetch a new one).
    const login = backend.log.find((r) => r.path === '/api/auth/login');
    expect(login?.body).toBe('username=admin&password=12345678');
    expect(backend.audit[0].eventType).toBe('LOGIN_SUCCESS');
  });

  it('shows each zone risk and warns about incomplete reading history', async () => {
    const backend = new FakeBackend({ signedInAs: 'admin' });
    const user = userEvent.setup();
    start(backend);

    const zones = await screen.findByRole('region', { name: 'Zones' });
    await within(zones).findByRole('button', { name: /RAW-MEAT .* risk HIGH$/ });
    expect(
      within(zones).getByRole('button', { name: /VEG-COOL .* risk MEDIUM incomplete history/ }),
    ).toBeInTheDocument();
    expect(within(zones).getByRole('button', { name: /EGGS .* risk LOW incomplete history/ })).toBeInTheDocument();
    expect(within(zones).getAllByText('Incomplete history')).toHaveLength(2);
    expect(within(zones).getByText('Pending')).toBeInTheDocument();
    expect(screen.getByText(/Incomplete reading history in 2 zones/).closest('.notice')).toHaveTextContent(
      'VEG-COOL, EGGS. Readings are missing',
    );

    // A LOW model rating after an earlier gap is explained as possibly understated.
    await user.click(within(zones).getByRole('button', { name: /^EGGS/ }));
    const panel = await screen.findByRole('region', { name: 'Risk prediction' });
    expect(within(panel).getByText('ML model')).toBeInTheDocument();
    expect(within(panel).getByText(/gap earlier in this storage session/)).toBeInTheDocument();
    expect(within(panel).getByText(/Monitoring was stopped/)).toBeInTheDocument();
    expect(within(panel).getByText(/may be lower than the real risk/)).toBeInTheDocument();

    // A reading straight after a gap was rated by the rules.
    await user.click(within(zones).getByRole('button', { name: /^VEG-COOL/ }));
    await waitFor(() =>
      expect(screen.getByRole('region', { name: 'Risk prediction' })).toHaveTextContent('Rules fallback'),
    );
    expect(screen.getByText(/ML model was not used/)).toBeInTheDocument();

    // Complete history: no warning.
    await user.click(within(zones).getByRole('button', { name: /^RAW-MEAT/ }));
    await waitFor(() => expect(screen.getByRole('region', { name: 'Risk prediction' })).toHaveTextContent('Complete'));
    expect(screen.queryByText(/may be lower than the real risk/)).not.toBeInTheDocument();
    expect(backend.log.some((r) => r.path === '/api/readings/latest')).toBe(true);
  });

  it('rejects a wrong password with the server message', async () => {
    const user = userEvent.setup();
    start(new FakeBackend());
    await signIn(user, 'admin', 'wrong');
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password.');
  });

  it('returns to sign-in when the server ends the session', async () => {
    const backend = new FakeBackend({ signedInAs: 'olivia' });
    start(backend);
    await screen.findByRole('region', { name: 'Zones' });
    backend.session = null; // e.g. an administrator reset the password
    expect(await screen.findByText(/Your session has ended/, {}, { timeout: 4000 })).toBeInTheDocument();
  });

  it('hides administrator pages from operators', async () => {
    const backend = new FakeBackend({ signedInAs: 'olivia' });
    start(backend, '#/users');
    await screen.findByRole('heading', { name: 'Zone overview' });
    expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Audit log' })).not.toBeInTheDocument();
  });

  it('signs out', async () => {
    const backend = new FakeBackend({ signedInAs: 'admin' });
    const user = userEvent.setup();
    start(backend);
    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(backend.session).toBeNull();
    expect(backend.audit[0].eventType).toBe('LOGOUT');
  });
});

describe('alerts', () => {
  it('acknowledges an active alert with a note', async () => {
    const backend = new FakeBackend({ signedInAs: 'olivia' });
    const user = userEvent.setup();
    start(backend, '#/alerts');

    const row = (await screen.findByText(/Temperature 4.6 °C/)).closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Acknowledge' }));
    const dialog = screen.getByRole('dialog', { name: 'Acknowledge alert #3' });
    await user.type(within(dialog).getByLabelText(/Note/), 'Checked the door seal.');
    await user.click(within(dialog).getByRole('button', { name: 'Acknowledge' }));

    expect(await screen.findByText('Alert #3 acknowledged.')).toBeInTheDocument();
    expect(backend.alerts.find((a) => a.id === 3)?.acknowledgment).toMatchObject({
      username: 'olivia',
      note: 'Checked the door seal.',
    });
    expect(backend.audit[0]).toMatchObject({ eventType: 'ALERT_ACKNOWLEDGED', actorUsername: 'olivia', targetId: '3' });
    expect(await screen.findByText('“Checked the door seal.”')).toBeInTheDocument();
  });

  it('filters by state', async () => {
    const backend = new FakeBackend({ signedInAs: 'admin' });
    const user = userEvent.setup();
    start(backend, '#/alerts');
    await screen.findByText(/Temperature 4.6 °C/);
    await user.selectOptions(screen.getByLabelText('State'), 'RESOLVED');
    await waitFor(() => expect(screen.queryByText(/Temperature 4.6 °C/)).not.toBeInTheDocument());
    expect(backend.log.at(-1)?.path).toContain('state=RESOLVED');
  });
});

describe('thresholds', () => {
  it('lets an operator request a change that an administrator approves', async () => {
    const backend = new FakeBackend({ signedInAs: 'olivia' });
    const user = userEvent.setup();
    const view = start(backend, '#/thresholds');

    const row = (await screen.findByText('EGGS')).closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Request change' }));
    const dialog = screen.getByRole('dialog');
    const upper = within(dialog).getByLabelText('Temperature upper (°C)');
    await user.clear(upper);
    await user.type(upper, '5');
    await user.click(within(dialog).getByRole('button', { name: 'Submit request' }));

    expect(await screen.findByText(/sent for administrator approval/)).toBeInTheDocument();
    expect(backend.thresholds.get('ESP32-SIM-004')?.temperatureUpperC).toBe(4); // unchanged until approved
    view.unmount();

    // An administrator approves it.
    backend.session = backend.users.find((u) => u.username === 'admin')!;
    start(backend, '#/thresholds');
    const requestRow = (await screen.findByRole('button', { name: 'Approve' })).closest('tr')!;
    expect(requestRow).toHaveTextContent('ESP32-SIM-004');
    await user.click(within(requestRow).getByRole('button', { name: 'Approve' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Approve' }));

    expect(await screen.findByText(/approved/)).toBeInTheDocument();
    expect(backend.thresholds.get('ESP32-SIM-004')?.temperatureUpperC).toBe(5);
    expect(backend.audit[0].eventType).toBe('THRESHOLD_CHANGE_APPROVED');
  });

  it('applies an administrator change at once and validates input first', async () => {
    const backend = new FakeBackend({ signedInAs: 'admin' });
    const user = userEvent.setup();
    start(backend, '#/thresholds');

    const row = (await screen.findByText('FROZEN')).closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));
    const dialog = screen.getByRole('dialog');
    const lower = within(dialog).getByLabelText('Temperature lower (°C)');
    await user.clear(lower);
    await user.type(lower, '-10');
    await user.click(within(dialog).getByRole('button', { name: 'Apply limits' }));
    expect(within(dialog).getByText(/lower temperature limit must be below/)).toBeInTheDocument();
    expect(backend.log.some((r) => r.method === 'PUT')).toBe(false);

    await user.clear(lower);
    await user.type(lower, '-26');
    await user.click(within(dialog).getByRole('button', { name: 'Apply limits' }));
    expect(await screen.findByText(/are in effect/)).toBeInTheDocument();
    expect(backend.thresholds.get('ESP32-SIM-005')).toMatchObject({
      temperatureLowerC: -26,
      source: 'CUSTOM',
      changedBy: 'admin',
    });
  });
});

describe('administration', () => {
  it('creates a user and shows the generated password once', async () => {
    const backend = new FakeBackend({ signedInAs: 'admin' });
    const user = userEvent.setup();
    start(backend, '#/users');

    await user.click(await screen.findByRole('button', { name: 'New user' }));
    const dialog = screen.getByRole('dialog', { name: 'New user' });
    await user.type(within(dialog).getByLabelText('Username'), 'sam');
    await user.click(within(dialog).getByRole('button', { name: 'Create' }));

    const shown = await screen.findByTestId('generated-password');
    expect(shown.textContent).toBe(backend.users.find((u) => u.username === 'sam')?.password);
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(await screen.findByText('sam')).toBeInTheDocument();
    expect(screen.queryByTestId('generated-password')).not.toBeInTheDocument();
  });

  it('filters the audit log by event type', async () => {
    const backend = new FakeBackend({ signedInAs: 'admin' });
    const user = userEvent.setup();
    start(backend, '#/audit');
    await screen.findByText('Storage session started');
    await user.selectOptions(screen.getByLabelText('Event'), 'LOGIN_SUCCESS');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('No audit events match these filters.')).toBeInTheDocument();
    expect(backend.log.at(-1)?.path).toContain('eventType=LOGIN_SUCCESS');
  });
});
