BEGIN;

-- Name and email of the server owner who made each change, as they were at that moment.
ALTER TABLE provisioning_audit_log
    ADD COLUMN IF NOT EXISTS actor_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS actor_email VARCHAR(254);

COMMIT;
