BEGIN;

-- Conexion con el sitio WordPress de la empresa (una sola fila, id = 1). El secreto compartido se guarda sellado
-- (enc:v1:...), asi que no lleva regla de formato ni de largo.
CREATE TABLE ext_wordpress__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    site_url TEXT CHECK (site_url IS NULL OR site_url ~ '^https?://'),
    secret TEXT,
    site_name TEXT NOT NULL DEFAULT '',
    last_event_at TIMESTAMPTZ,
    last_error TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Agentes del sitio y el usuario de IDC al que corresponde cada uno.
CREATE TABLE ext_wordpress__agents (
    wp_user_id BIGINT PRIMARY KEY CHECK (wp_user_id > 0),
    name TEXT NOT NULL DEFAULT '',
    email TEXT NOT NULL DEFAULT '',
    -- Usuario de IDC (application_users.id). Sin clave foranea: una migracion de extension no puede apuntar a tablas del nucleo.
    user_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
