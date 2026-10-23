BEGIN;

CREATE TABLE server_modules (
    module_id TEXT PRIMARY KEY CHECK (length(trim(module_id)) > 0),
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) > 0),
    description TEXT NOT NULL DEFAULT '',
    version TEXT NOT NULL DEFAULT '0.1.0',
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    package_url TEXT,
    package_sha256 TEXT,
    installed_package_path TEXT,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX server_modules_locked_idx
    ON server_modules (locked, module_id);

COMMIT;
