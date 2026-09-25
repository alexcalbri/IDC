BEGIN;

CREATE TABLE tenant_modules (
    module_id VARCHAR(63) NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('installed', 'enabled', 'disabled')),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enabled_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),

    CONSTRAINT tenant_modules_pkey
        PRIMARY KEY (module_id),

    CONSTRAINT tenant_modules_enabled_at_check
        CHECK ((status = 'enabled' AND enabled_at IS NOT NULL) OR status <> 'enabled')
);

CREATE INDEX tenant_modules_status_idx
    ON tenant_modules (status, module_id);

INSERT INTO tenant_modules (module_id, display_name, status, enabled_at)
VALUES
    ('clientes', 'Clientes', 'enabled', CURRENT_TIMESTAMP),
    ('hostpot', 'Hostpot', 'disabled', NULL),
    ('crm', 'CRM', 'disabled', NULL);

COMMIT;
