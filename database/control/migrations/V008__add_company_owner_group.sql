BEGIN;

-- Companies of the same business owner share an owner group: a duplicated company joins the group
-- of the one it was copied from, so its owner can switch between them without signing in again.
-- It is an explicit link on purpose: users can change their own email, so it never decides ownership.
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS owner_group UUID;

UPDATE companies SET owner_group = id WHERE owner_group IS NULL;

ALTER TABLE companies
    ALTER COLUMN owner_group SET NOT NULL;

CREATE INDEX IF NOT EXISTS companies_owner_group_idx ON companies (owner_group);

COMMIT;
