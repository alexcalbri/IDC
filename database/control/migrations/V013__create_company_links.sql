BEGIN;

-- Companies of the same business owner that the owner links on purpose for one use (purpose), for
-- example 'clientes': linked companies share one customer table. Only companies of the same owner
-- group can be linked; the server checks it when linking and every read joins companies again, so a
-- company that leaves the owner group stops being linked at once.
-- link_group lets an owner keep several separate groups later; today every linked company of an
-- owner group uses one link group per purpose.
CREATE TABLE IF NOT EXISTS company_links (
    purpose VARCHAR(63) NOT NULL CHECK (purpose ~ '^[a-z][a-z0-9_]{0,62}$'),
    company_code VARCHAR(63) NOT NULL REFERENCES companies (code) ON DELETE CASCADE ON UPDATE CASCADE,
    owner_group UUID NOT NULL,
    link_group UUID NOT NULL,
    linked_by TEXT NOT NULL DEFAULT '' CHECK (length(linked_by) <= 300),
    linked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (purpose, company_code)
);

CREATE INDEX IF NOT EXISTS company_links_group_idx ON company_links (purpose, link_group);

COMMIT;
