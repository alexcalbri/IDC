BEGIN;

-- Llave de datos de esta empresa, envuelta (AES-256-GCM) con IDC_MASTER_KEY. Una sola fila.
CREATE TABLE IF NOT EXISTS tenant_data_keys (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    wrapped_key TEXT NOT NULL CHECK (length(wrapped_key) > 0),
    key_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


-- Extensiones instaladas en esta empresa (EXTENSIONS.md).
CREATE TABLE IF NOT EXISTS tenant_extensions (
    extension_id VARCHAR(63) PRIMARY KEY CHECK (extension_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    module_id VARCHAR(63) NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    version TEXT NOT NULL CHECK (length(trim(version)) > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('installed', 'enabled', 'disabled', 'incompatible', 'removed')),
    installed_by TEXT,
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enabled_at TIMESTAMPTZ,

    CONSTRAINT tenant_extensions_enabled_at_check
        CHECK (status <> 'enabled' OR enabled_at IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS tenant_extensions_module_idx ON tenant_extensions (module_id, status);

-- Ajustes de cada extension; los secretos se guardan sellados (enc:v1:...) y secret = true.
CREATE TABLE IF NOT EXISTS tenant_extension_settings (
    extension_id VARCHAR(63) NOT NULL REFERENCES tenant_extensions(extension_id) ON DELETE CASCADE,
    key VARCHAR(127) NOT NULL CHECK (length(trim(key)) > 0),
    value TEXT NOT NULL,
    secret BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (extension_id, key)
);

-- Todo lo que una extension crea, para que desinstalar sepa que tocar.
CREATE TABLE IF NOT EXISTS tenant_extension_objects (
    extension_id VARCHAR(63) NOT NULL REFERENCES tenant_extensions(extension_id) ON DELETE CASCADE,
    kind VARCHAR(63) NOT NULL CHECK (length(trim(kind)) > 0),
    object_ref TEXT NOT NULL CHECK (length(trim(object_ref)) > 0),
    option_id VARCHAR(63),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (extension_id, kind, object_ref)
);

CREATE INDEX IF NOT EXISTS tenant_extension_objects_option_idx ON tenant_extension_objects (extension_id, option_id);

COMMIT;
