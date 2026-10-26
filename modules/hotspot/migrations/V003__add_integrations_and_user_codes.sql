BEGIN;

-- Integrations offered by the hotspot module. 'network' rows are network controllers (exactly one
-- can be enabled: today UniFi, later MikroTik or others); 'module' rows connect the hotspot with
-- other modules (e.g. 'usuarios': WiFi access with the user's code). Only the business owner or a
-- server owner changes them.
CREATE TABLE hotspot_integrations (
    id VARCHAR(32) PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9_]{0,31}$'),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('network', 'module')),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX hotspot_integrations_one_network
    ON hotspot_integrations (kind)
    WHERE kind = 'network' AND enabled;

-- UniFi was the only controller so far: it stays enabled for every company.
INSERT INTO hotspot_integrations (id, kind, enabled) VALUES
    ('unifi', 'network', TRUE),
    ('usuarios', 'module', FALSE);

-- WiFi code of each user of this database (and, in the server company, of the server owners, whose
-- accounts live in the central database). Owned by the hotspot module: the users table is not changed.
CREATE TABLE hotspot_user_codes (
    user_id UUID PRIMARY KEY,
    user_code VARCHAR(8) NOT NULL CHECK (user_code ~ '^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}$'),
    server_owner BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT hotspot_user_codes_code_unique UNIQUE (user_code)
);

-- Networks of other companies that users of the server company may join with their code.
-- Kept in the server company's database and managed only by server owners.
CREATE TABLE hotspot_user_network_grants (
    id UUID PRIMARY KEY,
    company_code VARCHAR(63) NOT NULL,
    ssid TEXT NOT NULL CHECK (length(ssid) BETWEEN 1 AND 32),
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT hotspot_user_network_grants_unique UNIQUE (company_code, ssid, user_id)
);

-- Access by user code.
ALTER TABLE hotspot_networks DROP CONSTRAINT hotspot_networks_access_mode_check;
ALTER TABLE hotspot_networks ADD CONSTRAINT hotspot_networks_access_mode_check
    CHECK (access_mode IN ('formulario', 'codigo_cliente', 'codigo_usuario'));

ALTER TABLE hotspot_registrations DROP CONSTRAINT hotspot_registrations_access_mode_check;
ALTER TABLE hotspot_registrations ADD CONSTRAINT hotspot_registrations_access_mode_check
    CHECK (access_mode IN ('formulario', 'codigo_cliente', 'codigo_usuario'));

-- A registration belongs to a customer or to a user (who has no email or phone). user_company is
-- the company of the user: this one, or the server company for users it authorized here.
ALTER TABLE hotspot_registrations
    ALTER COLUMN customer_id DROP NOT NULL,
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN phone DROP NOT NULL,
    ADD COLUMN user_id UUID,
    ADD COLUMN user_company VARCHAR(63),
    ADD CONSTRAINT hotspot_registrations_owner
        CHECK ((customer_id IS NULL) <> (user_id IS NULL));

-- The reconnection interval of users is counted by user instead of by email.
ALTER TABLE hotspot_accesses
    ALTER COLUMN email DROP NOT NULL,
    ADD COLUMN user_id UUID,
    ADD COLUMN user_company VARCHAR(63);

CREATE INDEX hotspot_accesses_user_idx
    ON hotspot_accesses (user_company, user_id, created_at)
    WHERE authorized AND user_id IS NOT NULL;

COMMIT;
