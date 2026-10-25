BEGIN;

-- Singleton configuration of the company's captive portal.
CREATE TABLE hotspot_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    domain VARCHAR(253),

    network_name TEXT NOT NULL DEFAULT 'WiFi gratis',
    portal_title TEXT NOT NULL DEFAULT 'WiFi gratis',
    portal_subtitle TEXT NOT NULL DEFAULT 'Registrate en segundos y navega.',
    logo_url TEXT,
    primary_color CHAR(7) NOT NULL DEFAULT '#E4002B' CHECK (primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    text_color CHAR(7) NOT NULL DEFAULT '#FFFFFF' CHECK (text_color ~ '^#[0-9A-Fa-f]{6}$'),
    consent_text TEXT NOT NULL DEFAULT 'Acepto recibir promociones por correo electronico y los terminos del servicio WiFi.',
    terms_url TEXT,
    privacy_url TEXT,
    footer_text TEXT NOT NULL DEFAULT '',
    success_url TEXT,

    unifi_console_id TEXT,
    unifi_site_id TEXT,
    unifi_api_key TEXT,

    access_minutes INTEGER NOT NULL DEFAULT 60 CHECK (access_minutes BETWEEN 1 AND 10080),
    data_limit_mb INTEGER NOT NULL DEFAULT 0 CHECK (data_limit_mb BETWEEN 0 AND 1048576),
    rx_kbps INTEGER NOT NULL DEFAULT 0 CHECK (rx_kbps BETWEEN 0 AND 10000000),
    tx_kbps INTEGER NOT NULL DEFAULT 0 CHECK (tx_kbps BETWEEN 0 AND 10000000),
    reconnect_minutes INTEGER NOT NULL DEFAULT 1440 CHECK (reconnect_minutes BETWEEN 0 AND 525600),
    code_ttl_minutes INTEGER NOT NULL DEFAULT 15 CHECK (code_ttl_minutes BETWEEN 1 AND 1440),

    smtp_mode VARCHAR(16) NOT NULL DEFAULT 'servidor' CHECK (smtp_mode IN ('servidor', 'empresa')),
    smtp_host TEXT,
    smtp_port INTEGER NOT NULL DEFAULT 587 CHECK (smtp_port BETWEEN 1 AND 65535),
    smtp_username TEXT,
    smtp_password TEXT,
    smtp_from TEXT,
    smtp_security VARCHAR(16) NOT NULL DEFAULT 'starttls' CHECK (smtp_security IN ('starttls', 'ssl', 'none')),
    email_subject TEXT NOT NULL DEFAULT 'Tu codigo de acceso WiFi',

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO hotspot_settings (id) VALUES (1);

-- One row per portal form submission. The guest is also kept in Clientes.
CREATE TABLE hotspot_registrations (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id),
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    email TEXT NOT NULL CHECK (length(trim(email)) > 0),
    phone TEXT NOT NULL CHECK (length(trim(phone)) > 0),
    birthday DATE,
    mac VARCHAR(17) NOT NULL CHECK (mac ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'),
    ap_mac VARCHAR(17),
    ssid TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX hotspot_registrations_created_idx
    ON hotspot_registrations (created_at);

CREATE INDEX hotspot_registrations_email_idx
    ON hotspot_registrations (lower(email));

-- One-time codes sent by email. Only the SHA-256 of the code is stored.
CREATE TABLE hotspot_codes (
    id UUID PRIMARY KEY,
    registration_id UUID NOT NULL REFERENCES hotspot_registrations(id) ON DELETE CASCADE,
    code_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX hotspot_codes_registration_idx
    ON hotspot_codes (registration_id);

-- Every authorization attempt against UniFi. Successful rows drive the
-- reconnection interval per email.
CREATE TABLE hotspot_accesses (
    id UUID PRIMARY KEY,
    registration_id UUID NOT NULL REFERENCES hotspot_registrations(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    mac VARCHAR(17) NOT NULL,
    authorized BOOLEAN NOT NULL,
    minutes INTEGER NOT NULL,
    unifi_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX hotspot_accesses_email_idx
    ON hotspot_accesses (lower(email), created_at)
    WHERE authorized;

CREATE INDEX hotspot_accesses_created_idx
    ON hotspot_accesses (created_at);

COMMIT;
