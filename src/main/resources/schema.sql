-- Smart Cold Storage Monitor schema (SQLite).
--
-- Applied on every startup by Spring's SQL initializer. Every statement must be idempotent
-- (CREATE ... IF NOT EXISTS): normal startup must never drop or reset data.
-- Timestamps are UTC ISO 8601 text (e.g. 2026-11-04T18:30:00.123Z).
-- Remaining design tables (predictions) are added here as their features are implemented.

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

-- Versioned temperature and humidity limits (FR-04): every change inserts a new row and never
-- rewrites an old one; the session's current version is its row with the highest id. A reading
-- records the version that applied to it. changed_by is NULL for a version the system created
-- (the location default given to a new session). UNIQUE (id, session_id) lets readings check that
-- the version is their session's.
-- The humidity columns were added after the table was first released, so they are nullable and
-- also listed in setup/SchemaUpgradeOnStartup; the application always writes both. Their checks
-- require both or neither, each within 0..100, and lower < upper.
CREATE TABLE IF NOT EXISTS threshold_settings (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id         INTEGER NOT NULL REFERENCES storage_sessions (id),
    lower_c            REAL    NOT NULL,
    upper_c            REAL    NOT NULL,
    effective_from     TEXT    NOT NULL
        CHECK (effective_from GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    changed_by         INTEGER REFERENCES users (id),
    lower_humidity_pct REAL CHECK (lower_humidity_pct IS NULL OR lower_humidity_pct BETWEEN 0 AND 100),
    upper_humidity_pct REAL CHECK ((upper_humidity_pct IS NULL) = (lower_humidity_pct IS NULL) AND (upper_humidity_pct IS NULL OR (upper_humidity_pct BETWEEN 0 AND 100 AND lower_humidity_pct < upper_humidity_pct))),
    CHECK (lower_c BETWEEN -273.15 AND 1000 AND upper_c BETWEEN -273.15 AND 1000 AND lower_c < upper_c),
    UNIQUE (id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_threshold_settings_session_time
    ON threshold_settings (session_id, effective_from);

-- An operator's proposed threshold change for one device, applied only when an administrator
-- approves it. base_threshold_id is the version that was current when the request was made;
-- approval is refused when the device's current version is no longer that one.
-- applied_threshold_id is the version an approval created. Status changes keep the row: a request
-- is never deleted. idx_threshold_change_requests_one_pending allows one PENDING request per device.
CREATE TABLE IF NOT EXISTS threshold_change_requests (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id            TEXT    NOT NULL REFERENCES devices (id),
    base_threshold_id    INTEGER NOT NULL REFERENCES threshold_settings (id),
    lower_c              REAL    NOT NULL,
    upper_c              REAL    NOT NULL,
    lower_humidity_pct   REAL    NOT NULL,
    upper_humidity_pct   REAL    NOT NULL,
    status               TEXT    NOT NULL
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'SUPERSEDED')),
    requested_by         INTEGER NOT NULL REFERENCES users (id),
    requested_at         TEXT    NOT NULL
        CHECK (requested_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    reviewed_by          INTEGER REFERENCES users (id),
    reviewed_at          TEXT
        CHECK (reviewed_at IS NULL OR reviewed_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    review_note          TEXT    CHECK (review_note IS NULL OR length(review_note) <= 500),
    applied_threshold_id INTEGER REFERENCES threshold_settings (id),
    CHECK (lower_c BETWEEN -273.15 AND 1000 AND upper_c BETWEEN -273.15 AND 1000 AND lower_c < upper_c),
    CHECK (lower_humidity_pct BETWEEN 0 AND 100 AND upper_humidity_pct BETWEEN 0 AND 100
           AND lower_humidity_pct < upper_humidity_pct),
    CHECK ((status = 'PENDING') = (reviewed_at IS NULL)),
    CHECK ((status = 'APPROVED') = (applied_threshold_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_threshold_change_requests_one_pending
    ON threshold_change_requests (device_id) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_threshold_change_requests_time
    ON threshold_change_requests (requested_at);

-- One accepted sensor reading. UNIQUE (device_id, measured_at) makes a retried submission find
-- the existing row instead of inserting a duplicate (its index also serves latest-per-device and
-- out-of-order checks). The composite foreign keys keep the session on the same device and the
-- threshold version on the same session. threshold_id is the session's current version when the
-- reading was accepted (NULL only if the session had none). The value CHECKs are a storage backstop (they also reject infinity),
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

-- ---------------------------------------------------------------------------------------------
-- Alerts (FR-04 threshold excursions and, later, FR-05 risk warnings) share one table, one severity
-- scale and one acknowledgment flow (FR-06). One row is one continuous episode, not one reading.
--   kind THRESHOLD: a temperature or humidity value outside its limits. Written in the reading's
--     transaction; source is THRESHOLD; one active alert per session and metric. metric, direction,
--     opening_threshold_id (the version the opening reading was checked against), limit_value (the
--     crossed limit of that version) and the opening/last/peak values are required. peak_value is the
--     value furthest beyond the limit (highest for ABOVE, lowest for BELOW).
--   kind RISK: a risk level of MEDIUM or above from the risk predictor (source MODEL or RULES), written
--     after the reading commits; one active alert per session. model_or_rule_version is required.
-- Which severity a kind gets is application logic, not a CHECK, so it can change without rebuilding
-- the table (SQLite cannot alter a CHECK). opened_at and last_seen_at are measured times of the
-- readings that opened and last confirmed the episode; resolved_at is the measured time of the reading
-- that ended it. resolve_reason: RECOVERED (back in range / risk LOW), SUPERSEDED (replaced by a new
-- alert of the same kind, e.g. a jump from below to above), SESSION_ENDED.
-- Deliberately no foreign key to readings or predictions: those rows are cleared on startup by default
-- (ReadingRetentionService), while alerts and their acknowledgment audit trail must stay. The values
-- the alert needs are copied in. The composite foreign keys keep the session on the device and the
-- threshold version on the session.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alerts (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id            INTEGER NOT NULL,
    device_id             TEXT    NOT NULL,
    kind                  TEXT    NOT NULL CHECK (kind IN ('THRESHOLD', 'RISK')),
    severity              TEXT    NOT NULL CHECK (severity IN ('MEDIUM', 'HIGH')),
    source                TEXT    NOT NULL CHECK (source IN ('THRESHOLD', 'MODEL', 'RULES')),
    opened_at             TEXT    NOT NULL
        CHECK (opened_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    last_seen_at          TEXT    NOT NULL
        CHECK (last_seen_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    resolved_at           TEXT
        CHECK (resolved_at IS NULL OR resolved_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    resolve_reason        TEXT    CHECK (resolve_reason IN ('RECOVERED', 'SUPERSEDED', 'SESSION_ENDED')),

    metric                TEXT    CHECK (metric IN ('TEMPERATURE', 'HUMIDITY')),
    direction             TEXT    CHECK (direction IN ('BELOW', 'ABOVE')),
    opening_threshold_id  INTEGER,
    limit_value           REAL,
    opening_value         REAL,
    last_value            REAL,
    peak_value            REAL,

    model_or_rule_version TEXT    CHECK (model_or_rule_version IS NULL OR length(model_or_rule_version) BETWEEN 1 AND 100),
    quality_flag          TEXT    CHECK (quality_flag IS NULL OR length(quality_flag) BETWEEN 1 AND 64),

    acknowledged_by       INTEGER REFERENCES users (id),
    acknowledged_at       TEXT
        CHECK (acknowledged_at IS NULL OR acknowledged_at GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z'),
    note                  TEXT    CHECK (note IS NULL OR length(note) <= 500),

    FOREIGN KEY (session_id, device_id) REFERENCES storage_sessions (id, device_id),
    FOREIGN KEY (opening_threshold_id, session_id) REFERENCES threshold_settings (id, session_id),
    CHECK (last_seen_at >= opened_at AND (resolved_at IS NULL OR resolved_at >= last_seen_at)),
    CHECK ((resolved_at IS NULL) = (resolve_reason IS NULL)),
    CHECK ((acknowledged_at IS NULL) = (acknowledged_by IS NULL)),
    CHECK (note IS NULL OR acknowledged_at IS NOT NULL),
    CHECK (kind <> 'THRESHOLD' OR (source = 'THRESHOLD' AND metric IS NOT NULL AND direction IS NOT NULL
           AND opening_threshold_id IS NOT NULL AND limit_value IS NOT NULL AND opening_value IS NOT NULL
           AND last_value IS NOT NULL AND peak_value IS NOT NULL
           AND model_or_rule_version IS NULL AND quality_flag IS NULL)),
    CHECK (kind <> 'RISK' OR (source IN ('MODEL', 'RULES') AND model_or_rule_version IS NOT NULL
           AND metric IS NULL AND direction IS NULL AND opening_threshold_id IS NULL AND limit_value IS NULL
           AND opening_value IS NULL AND last_value IS NULL AND peak_value IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_alerts_one_active_threshold
    ON alerts (session_id, metric) WHERE kind = 'THRESHOLD' AND resolved_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_alerts_one_active_risk
    ON alerts (session_id) WHERE kind = 'RISK' AND resolved_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_alerts_session_time ON alerts (session_id, opened_at);
CREATE INDEX IF NOT EXISTS idx_alerts_opened_at ON alerts (opened_at);
