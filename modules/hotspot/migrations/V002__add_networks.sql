BEGIN;

-- WiFi networks (SSID) with their own way of getting access. UniFi sends the SSID the guest joined
-- in the portal redirect; an SSID that is not listed here uses the registration form.
CREATE TABLE hotspot_networks (
    id UUID PRIMARY KEY,
    ssid TEXT NOT NULL CHECK (length(ssid) BETWEEN 1 AND 32),
    -- formulario: registration form + emailed code. codigo_cliente: the customer's code from Clientes.
    access_mode VARCHAR(32) NOT NULL DEFAULT 'formulario'
        CHECK (access_mode IN ('formulario', 'codigo_cliente')),
    -- Customer types (customers.customer_type) allowed to connect by code; empty = any type.
    customer_types TEXT[] NOT NULL DEFAULT '{}',
    -- NULL uses the access and reconnection times of the general configuration.
    access_minutes INTEGER CHECK (access_minutes BETWEEN 1 AND 10080),
    reconnect_minutes INTEGER CHECK (reconnect_minutes BETWEEN 0 AND 525600),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT hotspot_networks_ssid_unique UNIQUE (ssid)
);

-- How each registration got access; code access registers the customer's own contact data.
ALTER TABLE hotspot_registrations
    ADD COLUMN access_mode VARCHAR(32) NOT NULL DEFAULT 'formulario'
        CHECK (access_mode IN ('formulario', 'codigo_cliente'));

-- Wrong codes typed in the portal, used to slow down guessing per device and per company.
CREATE TABLE hotspot_code_attempts (
    id UUID PRIMARY KEY,
    mac VARCHAR(17) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX hotspot_code_attempts_mac_idx
    ON hotspot_code_attempts (mac, created_at);

CREATE INDEX hotspot_code_attempts_created_idx
    ON hotspot_code_attempts (created_at);

COMMIT;
