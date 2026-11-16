BEGIN;

-- A network that also exists on the gateways of linked companies (same owner group), for example
-- an SSID created through a UniFi Fabric. A device with live access on it in one company enters
-- the other company's portal without logging in again, for the time it has left there.
ALTER TABLE hotspot_networks
    ADD COLUMN shared BOOLEAN NOT NULL DEFAULT FALSE;

-- roamed_from: the company whose access the device continued here. Such a registration keeps the
-- guest's contact data but may have no customer or user of this company.
ALTER TABLE hotspot_registrations
    ADD COLUMN roamed_from VARCHAR(63),
    DROP CONSTRAINT hotspot_registrations_owner,
    ADD CONSTRAINT hotspot_registrations_owner CHECK (
        (customer_id IS NULL) <> (user_id IS NULL)
        OR (roamed_from IS NOT NULL AND customer_id IS NULL AND user_id IS NULL)
    );

CREATE INDEX hotspot_accesses_mac_idx ON hotspot_accesses (mac, created_at) WHERE authorized;

COMMIT;
