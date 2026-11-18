BEGIN;

-- Conexion de la empresa con Meta (WhatsApp Cloud API, Messenger e Instagram): una sola fila, id = 1. El token del usuario del
-- sistema de Meta Business es el unico que se escribe; el de la pagina se lee de el. Los secretos (app_secret, verify_token,
-- system_token, page_token, messenger_token, instagram_token, oauth_token) se guardan sellados (enc:v1:...), asi que no llevan
-- regla de formato ni de largo. Sin fila, Meta se ve apagado; la fila se crea al guardar.
CREATE TABLE ext_meta__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    -- Verifica el X-Hub-Signature-256 de cada llamada del webhook.
    app_secret TEXT,
    -- Se escribe igual en Meta al suscribir la URL de callback.
    verify_token TEXT,
    system_token TEXT,
    page_id TEXT CHECK (page_id IS NULL OR page_id ~ '^[0-9]{1,64}$'),
    page_name TEXT,
    page_token TEXT,
    instagram_account_id TEXT CHECK (instagram_account_id IS NULL OR instagram_account_id ~ '^[0-9]{1,64}$'),
    waba_id TEXT CHECK (waba_id IS NULL OR waba_id ~ '^[0-9]{1,64}$'),
    phone_number_id TEXT CHECK (phone_number_id IS NULL OR phone_number_id ~ '^[0-9]{1,64}$'),
    -- Coexistencia: las respuestas enviadas desde la app de WhatsApp Business se guardan como mensajes salientes.
    record_echoes BOOLEAN NOT NULL DEFAULT TRUE,
    -- Messenger e Instagram responden con el token de la pagina leido del token del sistema (shared_tokens) o cada uno con uno propio.
    shared_tokens BOOLEAN NOT NULL DEFAULT TRUE,
    messenger_token TEXT,
    instagram_token TEXT,
    -- @usuario de la cuenta de Instagram vinculada a la pagina, que se muestra en los ajustes.
    instagram_username TEXT,
    -- Como conecta la empresa Meta: 'own' (su propia app de Meta) o 'server' (la app del dueno del servidor, con Facebook Login).
    connection_mode TEXT NOT NULL DEFAULT 'own' CHECK (connection_mode IN ('own', 'server')),
    -- Modo servidor: token del inicio de sesion, para elegir entre varias paginas.
    oauth_token TEXT,
    -- Modo servidor: id en Meta de quien conecto (callbacks de desautorizacion y de eliminacion de datos).
    meta_user_id TEXT CHECK (meta_user_id IS NULL OR meta_user_id ~ '^[0-9]{1,64}$'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Ultimos 30 eventos del webhook y de la API, que se muestran en los ajustes. Nunca guarda tokens ni firmas.
CREATE TABLE ext_meta__log (
    id UUID PRIMARY KEY,
    event VARCHAR(40) NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ext_meta__log_created_idx ON ext_meta__log (created_at DESC);

-- App de Meta del dueno del servidor. Solo se lee en el espacio del servidor; existe en toda empresa. El secreto y el
-- verify token se guardan sellados.
CREATE TABLE ext_meta__server_app (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    app_id TEXT CHECK (app_id IS NULL OR app_id ~ '^[0-9]{1,64}$'),
    app_secret TEXT,
    verify_token TEXT,
    -- Configuraciones de Facebook Login for Business: Messenger e Instagram, y WhatsApp Embedded Signup.
    pages_config_id TEXT CHECK (pages_config_id IS NULL OR pages_config_id ~ '^[0-9]{1,64}$'),
    whatsapp_config_id TEXT CHECK (whatsapp_config_id IS NULL OR whatsapp_config_id ~ '^[0-9]{1,64}$'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Espacio del servidor: que empresa recibe los webhooks de cada activo de Meta de la app del servidor (page, instagram, waba)
-- y quien lo conecto (user). Un activo pertenece a una sola empresa.
CREATE TABLE ext_meta__routes (
    asset_id TEXT NOT NULL CHECK (asset_id ~ '^[0-9]{1,64}$'),
    kind TEXT NOT NULL CHECK (kind IN ('page', 'instagram', 'waba', 'user')),
    company_code TEXT NOT NULL CHECK (company_code ~ '^[a-z][a-z0-9_]{0,62}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (kind, asset_id, company_code)
);

-- Una sola empresa por pagina, cuenta de Instagram y cuenta de WhatsApp (una persona de Meta puede conectar varias).
CREATE UNIQUE INDEX ext_meta__routes_asset_idx ON ext_meta__routes (kind, asset_id) WHERE kind <> 'user';
CREATE INDEX ext_meta__routes_company_idx ON ext_meta__routes (company_code);

-- Enlaces de corta vida que abren la pagina de conexion de Meta de la empresa (se guardan con hash).
CREATE TABLE ext_meta__tickets (
    token_hash TEXT PRIMARY KEY,
    created_by TEXT NOT NULL DEFAULT '',
    expires_at TIMESTAMPTZ NOT NULL
);

COMMIT;
