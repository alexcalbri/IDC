BEGIN;

-- Settings of the module: a single row (id = 1).
CREATE TABLE eventos_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    spaces TEXT[] NOT NULL DEFAULT '{}',
    categories TEXT[] NOT NULL DEFAULT ARRAY['Taller', 'Música', 'Charla', 'Clase', 'Celebración', 'Boda', 'Retiro', 'Otro'],
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO eventos_settings (id) VALUES (1) ON CONFLICT DO NOTHING;

-- Events: public ones have capacity, prices and registrations; private ones have a client, an agreed amount and payments.
CREATE TABLE eventos_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('publico', 'privado')),
    title VARCHAR(200) NOT NULL CHECK (btrim(title) <> ''),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 10000),
    category VARCHAR(60) NOT NULL DEFAULT '',
    series VARCHAR(120) NOT NULL DEFAULT '',
    space VARCHAR(120) NOT NULL DEFAULT '',
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NULL,
    capacity INT NULL CHECK (capacity BETWEEN 1 AND 100000),
    host_name VARCHAR(160) NOT NULL DEFAULT '',
    responsible_user_id UUID NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'borrador' CHECK (status IN ('borrador', 'publicado', 'lleno', 'realizado', 'cancelado')),
    price_member NUMERIC(12, 2) NULL CHECK (price_member >= 0),
    price_public NUMERIC(12, 2) NULL CHECK (price_public >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    client_customer_id UUID NULL REFERENCES customers (id) ON DELETE SET NULL,
    agreed_amount NUMERIC(12, 2) NULL CHECK (agreed_amount >= 0),
    guests INT NULL CHECK (guests BETWEEN 1 AND 100000),
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 10000),
    import_key VARCHAR(200) NULL,
    cancelled_at TIMESTAMPTZ NULL,
    cancel_reason VARCHAR(500) NOT NULL DEFAULT '',
    created_by VARCHAR(160) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at IS NULL OR ends_at >= starts_at),
    CHECK (kind = 'publico' OR (capacity IS NULL AND price_member IS NULL AND price_public IS NULL)),
    CHECK (kind = 'privado' OR (client_customer_id IS NULL AND agreed_amount IS NULL))
);

CREATE UNIQUE INDEX eventos_events_import_key_idx ON eventos_events (import_key) WHERE import_key IS NOT NULL;
CREATE INDEX eventos_events_starts_idx ON eventos_events (starts_at);
CREATE INDEX eventos_events_status_starts_idx ON eventos_events (status, starts_at);
CREATE INDEX eventos_events_client_idx ON eventos_events (client_customer_id) WHERE client_customer_id IS NOT NULL;
CREATE INDEX eventos_events_series_idx ON eventos_events (lower(series));

-- Registrations of public events: one live registration (not cancelled) per customer and event.
CREATE TABLE eventos_registrations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES eventos_events (id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('inscrito', 'espera', 'cancelado')),
    seats INT NOT NULL DEFAULT 1 CHECK (seats BETWEEN 1 AND 100),
    price_kind VARCHAR(16) NOT NULL CHECK (price_kind IN ('miembro', 'general', 'cortesia')),
    amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    paid BOOLEAN NOT NULL DEFAULT false,
    paid_at TIMESTAMPTZ NULL,
    payment_note VARCHAR(200) NOT NULL DEFAULT '',
    attended_at TIMESTAMPTZ NULL,
    attended_by VARCHAR(160) NOT NULL DEFAULT '',
    waitlisted_at TIMESTAMPTZ NULL,
    note VARCHAR(1000) NOT NULL DEFAULT '',
    import_key VARCHAR(200) NULL,
    cancelled_at TIMESTAMPTZ NULL,
    created_by VARCHAR(160) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX eventos_registrations_live_idx ON eventos_registrations (event_id, customer_id) WHERE status <> 'cancelado';
CREATE UNIQUE INDEX eventos_registrations_import_key_idx ON eventos_registrations (import_key) WHERE import_key IS NOT NULL;
CREATE INDEX eventos_registrations_event_idx ON eventos_registrations (event_id, status, waitlisted_at);
CREATE INDEX eventos_registrations_customer_idx ON eventos_registrations (customer_id);

-- Payments (abonos) of private events.
CREATE TABLE eventos_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES eventos_events (id) ON DELETE CASCADE,
    paid_on DATE NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    method VARCHAR(40) NOT NULL DEFAULT '',
    note VARCHAR(500) NOT NULL DEFAULT '',
    import_key VARCHAR(200) NULL,
    recorded_by VARCHAR(160) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX eventos_payments_import_key_idx ON eventos_payments (import_key) WHERE import_key IS NOT NULL;
CREATE INDEX eventos_payments_event_idx ON eventos_payments (event_id, paid_on);

COMMIT;
