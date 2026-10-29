BEGIN;

-- Cloudbeds PMS: free rooms for the lead's dates, reservations created from the CRM, welcome email
-- before check-in and archiving after check-out.
CREATE TABLE crm_cloudbeds_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    api_key TEXT,
    property_id TEXT CHECK (property_id IS NULL OR property_id ~ '^[0-9]{1,30}$'),
    -- Cloudbeds requires a payment method on every reservation; payments are handled in Cloudbeds.
    payment_method VARCHAR(10) NOT NULL DEFAULT 'cash' CHECK (payment_method IN ('cash', 'credit', 'ebanking', 'pay_pal')),
    welcome_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    welcome_template_id UUID REFERENCES crm_email_templates(id) ON DELETE SET NULL,
    welcome_days_before SMALLINT NOT NULL DEFAULT 1 CHECK (welcome_days_before BETWEEN 0 AND 60),
    -- Hour of the day (server time zone) from which the welcome email goes out.
    welcome_hour SMALLINT NOT NULL DEFAULT 9 CHECK (welcome_hour BETWEEN 0 AND 23),
    archive_on_checkout BOOLEAN NOT NULL DEFAULT TRUE,
    last_sync TIMESTAMPTZ,
    last_error TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO crm_cloudbeds_settings (id) VALUES (1);

-- Reservations created in Cloudbeds from a lead.
CREATE TABLE crm_reservations (
    id UUID PRIMARY KEY,
    lead_id UUID NOT NULL REFERENCES crm_leads(id) ON DELETE CASCADE,
    reservation_id TEXT NOT NULL CHECK (length(reservation_id) BETWEEN 1 AND 60),
    status VARCHAR(16) NOT NULL DEFAULT 'confirmed'
        CHECK (status IN ('not_confirmed', 'confirmed', 'canceled', 'checked_in', 'checked_out', 'no_show')),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL CHECK (end_date > start_date),
    total NUMERIC(14, 2),
    rooms TEXT NOT NULL DEFAULT '',
    guest_email TEXT NOT NULL DEFAULT '',
    welcome_sent_at TIMESTAMPTZ,
    checked_out_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT crm_reservations_unique UNIQUE (reservation_id)
);

CREATE INDEX crm_reservations_lead_idx ON crm_reservations (lead_id);
CREATE INDEX crm_reservations_active_idx ON crm_reservations (start_date) WHERE status NOT IN ('canceled', 'checked_out', 'no_show');

-- Leads whose stay ended: out of the pipeline and lists, still reachable from the customer.
ALTER TABLE crm_leads ADD COLUMN archived_at TIMESTAMPTZ;

COMMIT;
