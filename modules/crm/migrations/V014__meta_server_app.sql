BEGIN;

-- How the company connects Meta: 'own' (its own Meta app, typed in Integraciones) or 'server' (the
-- app of the server owner, through Facebook Login for Business and WhatsApp Embedded Signup).
ALTER TABLE crm_meta_settings
    ADD COLUMN IF NOT EXISTS connection_mode TEXT NOT NULL DEFAULT 'own' CHECK (connection_mode IN ('own', 'server')),
    -- Server mode: token of the Facebook login, kept to choose among several pages.
    ADD COLUMN IF NOT EXISTS oauth_token TEXT,
    -- Server mode: Meta id of the person who connected (deauthorize and data deletion callbacks).
    ADD COLUMN IF NOT EXISTS meta_user_id TEXT CHECK (meta_user_id IS NULL OR meta_user_id ~ '^[0-9]{1,64}$');

-- Meta app of the server owner. Only read in the server workspace; one row everywhere.
CREATE TABLE IF NOT EXISTS crm_meta_server_app (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    app_id TEXT CHECK (app_id IS NULL OR app_id ~ '^[0-9]{1,64}$'),
    app_secret TEXT,
    verify_token TEXT,
    -- Facebook Login for Business configurations: Messenger and Instagram, and WhatsApp Embedded Signup.
    pages_config_id TEXT CHECK (pages_config_id IS NULL OR pages_config_id ~ '^[0-9]{1,64}$'),
    whatsapp_config_id TEXT CHECK (whatsapp_config_id IS NULL OR whatsapp_config_id ~ '^[0-9]{1,64}$'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO crm_meta_server_app (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- Server workspace: which company receives the webhooks of each Meta asset of the server app
-- (page, instagram, waba) and who connected it (user). An asset belongs to one company only.
CREATE TABLE IF NOT EXISTS crm_meta_routes (
    asset_id TEXT NOT NULL CHECK (asset_id ~ '^[0-9]{1,64}$'),
    kind TEXT NOT NULL CHECK (kind IN ('page', 'instagram', 'waba', 'user')),
    company_code TEXT NOT NULL CHECK (company_code ~ '^[a-z][a-z0-9_]{0,62}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (kind, asset_id, company_code)
);

-- Only one company per page, Instagram account and WhatsApp account (a Meta user may connect several).
CREATE UNIQUE INDEX IF NOT EXISTS crm_meta_routes_asset_idx ON crm_meta_routes (kind, asset_id) WHERE kind <> 'user';
CREATE INDEX IF NOT EXISTS crm_meta_routes_company_idx ON crm_meta_routes (company_code);

-- Short-lived links that open the Meta connection page for the company (stored hashed).
CREATE TABLE IF NOT EXISTS crm_meta_tickets (
    token_hash TEXT PRIMARY KEY,
    created_by TEXT NOT NULL DEFAULT '',
    expires_at TIMESTAMPTZ NOT NULL
);

COMMIT;
