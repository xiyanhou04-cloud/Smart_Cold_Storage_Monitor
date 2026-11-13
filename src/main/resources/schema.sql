-- Smart Cold Storage Monitor schema (SQLite).
--
-- Applied on every startup by Spring's SQL initializer. Every statement must be idempotent
-- (CREATE ... IF NOT EXISTS): normal startup must never drop or reset data.
-- Timestamps are UTC ISO 8601 text (e.g. 2026-11-04T18:30:00.123Z).
-- Remaining design tables (alerts, predictions) are added here as their features are implemented.

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

-- ---------------------------------------------------------------------------------------------
-- Sensor data (FR-02): devices, storage sessions, threshold versions, readings.
-- Timestamp columns below must hold the fixed-width form written by SqlTimestamps.format
-- (yyyy-MM-ddTHH:mm:ss.SSSZ). The GLOB checks enforce it, because text ordering and the
-- UNIQUE (device_id, measured_at) retry check both depend on one canonical spelling per instant.
-- ---------------------------------------------------------------------------------------------

-- id is the device identifier the device sends (e.g. ESP32-SIM-001). The ingestion credential
-- is mapped to the device through application configuration, not stored here.
-- location_code is the storage zone the device is installed in (e.g. RAW-MEAT). It belongs to the
-- registered device, not to each reading, so a device cannot claim another zone. NULL means unknown.
-- A column added after a table was first released must also be listed in
-- setup/SchemaUpgradeOnStartup, because CREATE TABLE IF NOT EXISTS leaves an existing table as it is.
CREATE TABLE IF NOT EXISTS devices (
    id            TEXT    PRIMARY KEY CHECK (length(id) BETWEEN 1 AND 64),
    name          TEXT    NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    location_code TEXT    CHECK (location_code IS NULL OR length(location_code) BETWEEN 1 AND 64)
);

-- A storage session defines the storage-duration origin. ended_at IS NULL means active, and
-- idx_storage_sessions_one_active allows at most one active session per device.
-- UNIQUE (id, device_id) lets readings check that their session belongs to their device.
CREATE TABLE IF NOT EXISTS storage_sessions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id    TEXT    NOT NULL REFERENCES devices (id),
    product_type TEXT    NOT NULL CHECK (length(product_type) BETWEEN 1 AND 64),
    started_at   TEXT    NOT NULL
        CHECK (started_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    ended_at     TEXT
        CHECK (ended_at IS NULL OR ended_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    UNIQUE (id, device_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_storage_sessions_one_active
    ON storage_sessions (device_id) WHERE ended_at IS NULL;

-- Versioned temperature limits: every change inserts a new row and never rewrites an old one.
-- A reading records the version that applied to it. changed_by is NULL for a version the
-- system created. UNIQUE (id, session_id) lets readings check that the version is their session's.
CREATE TABLE IF NOT EXISTS threshold_settings (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     INTEGER NOT NULL REFERENCES storage_sessions (id),
    lower_c        REAL    NOT NULL,
    upper_c        REAL    NOT NULL,
    effective_from TEXT    NOT NULL
        CHECK (effective_from GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    changed_by     INTEGER REFERENCES users (id),
    CHECK (lower_c BETWEEN -273.15 AND 1000 AND upper_c BETWEEN -273.15 AND 1000 AND lower_c < upper_c),
    UNIQUE (id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_threshold_settings_session_time
    ON threshold_settings (session_id, effective_from);

-- One accepted sensor reading. UNIQUE (device_id, measured_at) makes a retried submission find
-- the existing row instead of inserting a duplicate (its index also serves latest-per-device and
-- out-of-order checks). The composite foreign keys keep the session on the same device and the
-- threshold version on the same session. threshold_id stays NULL until threshold versioning
-- (FR-04) is implemented. The value CHECKs are a storage backstop (they also reject infinity),
-- and the ingestion service applies the stricter plausibility rules.
CREATE TABLE IF NOT EXISTS readings (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id     TEXT    NOT NULL REFERENCES devices (id),
    session_id    INTEGER NOT NULL,
    measured_at   TEXT    NOT NULL
        CHECK (measured_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    received_at   TEXT    NOT NULL
        CHECK (received_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    temperature_c REAL    NOT NULL CHECK (temperature_c BETWEEN -273.15 AND 1000),
    humidity_pct  REAL    NOT NULL CHECK (humidity_pct BETWEEN 0 AND 100),
    threshold_id  INTEGER,
    UNIQUE (device_id, measured_at),
    FOREIGN KEY (session_id, device_id) REFERENCES storage_sessions (id, device_id),
    FOREIGN KEY (threshold_id, session_id) REFERENCES threshold_settings (id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_readings_session_time ON readings (session_id, measured_at);
