BEGIN;

-- Cloudbeds PMS: reservations created from the CRM. The Cloudbeds settings live in the Cloudbeds extension.
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
