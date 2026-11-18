BEGIN;

-- Encuentra24 Leads API: contactos de los anuncios de la empresa (mensajes, chats y llamadas) importados
-- cada 5 minutos. Cada id de lead de Encuentra24 se guarda como id del mensaje, asi que nada se repite.
CREATE TABLE ext_encuentra24__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    api_key TEXT,
    -- Slug de la empresa en Encuentra24 (p. ej. emerald-investment).
    slug TEXT CHECK (slug IS NULL OR slug ~ '^[a-z0-9][a-z0-9-]{0,99}$'),
    last_sync TIMESTAMPTZ,
    last_result TEXT NOT NULL DEFAULT '',
    last_error TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
