-- Smart Cold Storage Monitor schema (SQLite).
--
-- Applied on every startup by Spring's SQL initializer. Every statement must be idempotent
-- (CREATE ... IF NOT EXISTS): normal startup must never drop or reset data.
-- Timestamps are UTC ISO 8601 text (e.g. 2026-11-04T18:30:00.123Z).
-- Remaining design tables (devices, storage_sessions, threshold_settings, readings, alerts,
-- predictions) are added here as their features are implemented.

CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE CHECK (length(username) BETWEEN 3 AND 64),
    password_hash TEXT    NOT NULL,
    role          TEXT    NOT NULL CHECK (role IN ('OPERATOR', 'ADMIN')),
    enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at    TEXT    NOT NULL
);

-- Append-only: the application exposes no update or delete path for audit records.
-- actor_id is nullable so failed logins (no authenticated user) can still be recorded.
CREATE TABLE IF NOT EXISTS audit_events (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    actor_id           INTEGER REFERENCES users (id),
    attempted_username TEXT CHECK (attempted_username IS NULL OR length(attempted_username) <= 64),
    event_type         TEXT    NOT NULL,
    occurred_at        TEXT    NOT NULL,
    result             TEXT    NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    target_type        TEXT,
    target_id          TEXT,
    details            TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_events_occurred_at ON audit_events (occurred_at);
CREATE INDEX IF NOT EXISTS idx_audit_events_type_time ON audit_events (event_type, occurred_at);
