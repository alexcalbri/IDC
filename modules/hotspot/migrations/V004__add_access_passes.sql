BEGIN;

-- Contract for other modules (plugins) that give WiFi access to their customers: a hotel stay, a
-- membership, hours bought in a cowork... The hotspot defines the rules; each module decides how it
-- fills them. See "Access passes" in modules/hotspot/README.md.

-- A module registers itself once; it then shows in Hotspot > Integraciones, where the business owner
-- or a server owner enables it. Ids share the namespace of hotspot_integrations.
CREATE TABLE hotspot_pass_sources (
    id VARCHAR(32) PRIMARY KEY
        CHECK (id ~ '^[a-z][a-z0-9_]{0,31}$' AND id NOT IN ('hotspot', 'usuarios', 'unifi', 'mikrotik')),
    name VARCHAR(80) NOT NULL CHECK (length(trim(name)) > 0),
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- What a customer may use, written and kept up to date by the source module. Rules:
--   periodo: connects while now is in [valid_from, valid_until), with no reconnection wait; each
--            session lasts the network's access time without going past valid_until.
--   saldo:   minutes_balance is spent by every session (the authorized time, at most the network's
--            access time); no reconnection wait; optional valid_from/valid_until bound when it can be used.
-- source_ref is the module's own reference (reservation, membership...), unique per source, so the
-- module updates the same pass when dates or balance change.
CREATE TABLE hotspot_access_passes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id VARCHAR(32) NOT NULL REFERENCES hotspot_pass_sources(id) ON DELETE CASCADE,
    source_ref VARCHAR(128) NOT NULL CHECK (length(source_ref) > 0),
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    rule VARCHAR(16) NOT NULL CHECK (rule IN ('periodo', 'saldo')),
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    minutes_balance INTEGER CHECK (minutes_balance >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT hotspot_access_passes_ref_unique UNIQUE (source_id, source_ref),
    CONSTRAINT hotspot_access_passes_window CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT hotspot_access_passes_rule CHECK (
        (rule = 'periodo' AND valid_from IS NOT NULL AND valid_until IS NOT NULL AND minutes_balance IS NULL)
        OR (rule = 'saldo' AND minutes_balance IS NOT NULL)
    )
);

CREATE INDEX hotspot_access_passes_customer_idx
    ON hotspot_access_passes (customer_id, source_id);

-- Pass used by each access, for reports.
ALTER TABLE hotspot_accesses
    ADD COLUMN pass_id UUID REFERENCES hotspot_access_passes(id) ON DELETE SET NULL;

-- Customer-code networks choose who gets in: 'hotspot' (customers of type hotspot, with the hotspot
-- times and reconnection) and/or pass sources. Free customer types are gone: an ordinary customer
-- no longer connects by code.
ALTER TABLE hotspot_networks ADD COLUMN access_sources TEXT[] NOT NULL DEFAULT '{}';
UPDATE hotspot_networks SET access_sources = '{hotspot}' WHERE access_mode = 'codigo_cliente';
ALTER TABLE hotspot_networks DROP COLUMN customer_types;

COMMIT;
