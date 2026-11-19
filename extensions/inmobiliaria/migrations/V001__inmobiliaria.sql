BEGIN;

-- Ajustes de la extension (una sola fila, id = 1): moneda y comision por defecto.
CREATE TABLE ext_inmobiliaria__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    default_commission_pct NUMERIC(6,3) NOT NULL DEFAULT 5 CHECK (default_commission_pct >= 0 AND default_commission_pct <= 100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Inventario de propiedades. Los usuarios y los clientes van sin clave foranea (una migracion de extension no puede
-- apuntar a tablas del nucleo): el codigo comprueba que existan y que el usuario los vea.
CREATE TABLE ext_inmobiliaria__properties (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code TEXT NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$'),
    title TEXT NOT NULL CHECK (length(title) >= 1 AND length(title) <= 300),
    kind TEXT NOT NULL DEFAULT 'other' CHECK (kind IN ('land', 'house', 'condo', 'development_lot', 'commercial', 'other')),
    zone TEXT NOT NULL DEFAULT '' CHECK (length(zone) <= 120),
    development TEXT NOT NULL DEFAULT '' CHECK (length(development) <= 120),
    status TEXT NOT NULL DEFAULT 'intake' CHECK (status IN ('intake', 'for_sale', 'negotiation', 'sold', 'withdrawn')),
    listing_agent_id UUID,
    owner_customer_id UUID,
    lot_area_m2 NUMERIC(12,2) CHECK (lot_area_m2 IS NULL OR lot_area_m2 >= 0),
    built_area_m2 NUMERIC(12,2) CHECK (built_area_m2 IS NULL OR built_area_m2 >= 0),
    bedrooms SMALLINT CHECK (bedrooms IS NULL OR (bedrooms >= 0 AND bedrooms <= 100)),
    bathrooms NUMERIC(4,1) CHECK (bathrooms IS NULL OR (bathrooms >= 0 AND bathrooms <= 100)),
    price NUMERIC(14,2) CHECK (price IS NULL OR price >= 0),
    previous_price NUMERIC(14,2) CHECK (previous_price IS NULL OR previous_price >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 10000),
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 10000),
    wordpress_listing_id BIGINT CHECK (wordpress_listing_id IS NULL OR wordpress_listing_id > 0),
    published_on DATE,
    sold_on DATE,
    -- Clave estable de la fuente de una importacion (p. ej. monday:123): reimportar actualiza, no duplica.
    import_key VARCHAR(120) CHECK (import_key IS NULL OR (length(import_key) >= 1 AND length(import_key) <= 120)),
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ext_inmobiliaria__properties_status_idx ON ext_inmobiliaria__properties (status);
CREATE INDEX ext_inmobiliaria__properties_zone_idx ON ext_inmobiliaria__properties (zone);
CREATE INDEX ext_inmobiliaria__properties_kind_idx ON ext_inmobiliaria__properties (kind);
CREATE INDEX ext_inmobiliaria__properties_agent_idx ON ext_inmobiliaria__properties (listing_agent_id);
CREATE INDEX ext_inmobiliaria__properties_updated_idx ON ext_inmobiliaria__properties (updated_at);
-- El codigo es unico sin distinguir mayusculas; un listado del sitio web solo se vincula a una propiedad.
CREATE UNIQUE INDEX ext_inmobiliaria__properties_code_uq ON ext_inmobiliaria__properties (lower(code));
CREATE UNIQUE INDEX ext_inmobiliaria__properties_wordpress_uq ON ext_inmobiliaria__properties (wordpress_listing_id) WHERE wordpress_listing_id IS NOT NULL;
CREATE INDEX ext_inmobiliaria__properties_created_by_idx ON ext_inmobiliaria__properties (created_by);
CREATE UNIQUE INDEX ext_inmobiliaria__properties_import_uq ON ext_inmobiliaria__properties (import_key) WHERE import_key IS NOT NULL;

-- Enlaces de la propiedad por portal (sitio web, Craigslist, Encuentra24, Google Drive u otro).
CREATE TABLE ext_inmobiliaria__property_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL REFERENCES ext_inmobiliaria__properties (id) ON DELETE CASCADE,
    label TEXT NOT NULL CHECK (length(label) >= 1 AND length(label) <= 60),
    url TEXT NOT NULL CHECK (url ~ '^https?://' AND length(url) <= 2000),
    position INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0)
);

CREATE INDEX ext_inmobiliaria__property_links_property_idx ON ext_inmobiliaria__property_links (property_id);

-- Historial de precios: de aqui sale la insignia Rebajada.
CREATE TABLE ext_inmobiliaria__price_changes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL REFERENCES ext_inmobiliaria__properties (id) ON DELETE CASCADE,
    old_price NUMERIC(14,2) CHECK (old_price IS NULL OR old_price >= 0),
    new_price NUMERIC(14,2) CHECK (new_price IS NULL OR new_price >= 0),
    changed_by UUID,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    note TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 500)
);

CREATE INDEX ext_inmobiliaria__price_changes_property_idx ON ext_inmobiliaria__price_changes (property_id, changed_at DESC);

-- Etapas de las transacciones (la plantilla se crea desde el codigo al activar la extension).
CREATE TABLE ext_inmobiliaria__deal_stages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL CHECK (length(name) >= 1 AND length(name) <= 60),
    position INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0 AND position <= 999),
    outcome TEXT NOT NULL DEFAULT 'open' CHECK (outcome IN ('open', 'won', 'lost'))
);

-- Transacciones. Los porcentajes de cada parte son sobre la comision total.
CREATE TABLE ext_inmobiliaria__deals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id UUID NOT NULL REFERENCES ext_inmobiliaria__properties (id) ON DELETE RESTRICT,
    buyer_lead_id UUID,
    buyer_customer_id UUID,
    stage_id UUID NOT NULL REFERENCES ext_inmobiliaria__deal_stages (id) ON DELETE RESTRICT,
    agreed_price NUMERIC(14,2) CHECK (agreed_price IS NULL OR agreed_price >= 0),
    commission_pct NUMERIC(6,3) NOT NULL DEFAULT 0 CHECK (commission_pct >= 0 AND commission_pct <= 100),
    commission_amount NUMERIC(14,2) CHECK (commission_amount IS NULL OR commission_amount >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    estimated_close_on DATE,
    closed_on DATE,
    comments TEXT NOT NULL DEFAULT '' CHECK (length(comments) <= 5000),
    -- Clave estable de la fuente de una importacion (ver properties.import_key).
    import_key VARCHAR(120) CHECK (import_key IS NULL OR (length(import_key) >= 1 AND length(import_key) <= 120)),
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ext_inmobiliaria__deals_stage_idx ON ext_inmobiliaria__deals (stage_id);
CREATE INDEX ext_inmobiliaria__deals_property_idx ON ext_inmobiliaria__deals (property_id);
CREATE INDEX ext_inmobiliaria__deals_close_idx ON ext_inmobiliaria__deals (estimated_close_on);
CREATE INDEX ext_inmobiliaria__deals_lead_idx ON ext_inmobiliaria__deals (buyer_lead_id);
CREATE INDEX ext_inmobiliaria__deals_created_by_idx ON ext_inmobiliaria__deals (created_by);
CREATE UNIQUE INDEX ext_inmobiliaria__deals_import_uq ON ext_inmobiliaria__deals (import_key) WHERE import_key IS NOT NULL;

-- Partes de la transaccion: un agente interno (user_id), un cliente (customer_id) o un externo (name).
CREATE TABLE ext_inmobiliaria__deal_parties (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_id UUID NOT NULL REFERENCES ext_inmobiliaria__deals (id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('seller', 'buyer', 'listing_agent', 'buyer_agent', 'broker', 'seller_lawyer', 'buyer_lawyer', 'referral', 'other')),
    user_id UUID,
    customer_id UUID,
    name TEXT NOT NULL DEFAULT '' CHECK (length(name) <= 200),
    share_pct NUMERIC(6,3) NOT NULL DEFAULT 0 CHECK (share_pct >= 0 AND share_pct <= 100),
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 500),
    position INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0),
    CHECK (user_id IS NOT NULL OR customer_id IS NOT NULL OR length(name) >= 1)
);

CREATE INDEX ext_inmobiliaria__deal_parties_deal_idx ON ext_inmobiliaria__deal_parties (deal_id, user_id);
CREATE INDEX ext_inmobiliaria__deal_parties_user_idx ON ext_inmobiliaria__deal_parties (user_id);

COMMIT;
