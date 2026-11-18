BEGIN;

-- Extensiones conocidas por el servidor (EXTENSIONS.md): las incluidas en un modulo (bundled) y los
-- paquetes externos instalados. Cada empresa las instala por su cuenta en tenant_extensions.
CREATE TABLE IF NOT EXISTS server_extensions (
    id VARCHAR(63) PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9_]{0,62}$'),
    module_id VARCHAR(63) NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    version TEXT NOT NULL CHECK (length(trim(version)) > 0),
    min_module_version TEXT,
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN ('bundled', 'github')),
    -- Carpeta de GitHub fijada a un commit de 40 hex; las incluidas en un modulo no tienen URL.
    source_url TEXT,
    package_sha256 VARCHAR(64) CHECK (package_sha256 IS NULL OR package_sha256 ~ '^[0-9a-f]{64}$'),
    signed BOOLEAN NOT NULL DEFAULT FALSE,
    key_id TEXT,
    installed_package_path TEXT,
    manifest JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(manifest) = 'object'),
    status VARCHAR(16) NOT NULL DEFAULT 'installed' CHECK (status IN ('installed', 'pending_restart', 'removed')),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT server_extensions_github_url_check
        CHECK (source_type <> 'github' OR source_url ~ '/tree/[0-9a-f]{40}(/|$)')
);

CREATE INDEX IF NOT EXISTS server_extensions_module_idx ON server_extensions (module_id, status);

-- Paquetes sin firma que el server owner autorizo, uno por hash, con la confirmacion escrita.
CREATE TABLE IF NOT EXISTS extension_unsigned_authorizations (
    id BIGSERIAL PRIMARY KEY,
    package_sha256 VARCHAR(64) NOT NULL UNIQUE CHECK (package_sha256 ~ '^[0-9a-f]{64}$'),
    extension_id VARCHAR(63) NOT NULL CHECK (extension_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    source_url TEXT NOT NULL,
    actor VARCHAR(63) NOT NULL,
    confirmation_text TEXT NOT NULL CHECK (length(trim(confirmation_text)) > 0),
    authorized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Una operacion sobre una extension; las que se aplican a varias empresas llevan un registro por destino.
CREATE TABLE IF NOT EXISTS extension_operations (
    id BIGSERIAL PRIMARY KEY,
    actor VARCHAR(63) NOT NULL,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('install', 'configure', 'enable', 'disable', 'uninstall', 'apply')),
    extension_id VARCHAR(63) NOT NULL CHECK (extension_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    source_company VARCHAR(63),
    status VARCHAR(16) NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'done', 'partial', 'failed')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS extension_operations_extension_idx ON extension_operations (extension_id, started_at DESC);

CREATE TABLE IF NOT EXISTS extension_operation_targets (
    operation_id BIGINT NOT NULL REFERENCES extension_operations(id) ON DELETE CASCADE,
    company_code VARCHAR(63) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'applied', 'failed', 'skipped')),
    reason TEXT,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (operation_id, company_code)
);

CREATE INDEX IF NOT EXISTS extension_operation_targets_status_idx ON extension_operation_targets (operation_id, status);

-- Grupo vinculado: se suspende cuando cambia el correo del business owner de la empresa respecto al
-- que tenia al vincularla, hasta que el server owner lo revise.
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS group_suspended BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS group_owner_email VARCHAR(254);

COMMIT;
