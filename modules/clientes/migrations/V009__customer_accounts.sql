BEGIN;

-- Accounts linked to a customer card: the card is the person's record and an account of this
-- company (a user who also buys services, a real estate agent who buys a property) may be linked to
-- it. One account, one card; one account of each kind per card in this company. account_kind lets a
-- later customer account (for example a loyalty sign-in) link to the same card; today only 'user'.
-- account_id has no foreign key because application_users belongs to the Core: Clientes checks the
-- account when it writes. customer_id keeps its foreign key so a merge moves the link with the card.
-- A linked account gains no permission over its card or history.
CREATE TABLE customer_accounts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    account_kind VARCHAR(16) NOT NULL DEFAULT 'user' CHECK (account_kind IN ('user')),
    account_id UUID NOT NULL,
    linked_by TEXT NOT NULL DEFAULT '' CHECK (length(linked_by) <= 300),
    linked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX customer_accounts_account_idx ON customer_accounts (account_kind, account_id);
CREATE UNIQUE INDEX customer_accounts_customer_idx ON customer_accounts (customer_id, account_kind);

ALTER TABLE customer_activity DROP CONSTRAINT IF EXISTS customer_activity_kind_check;
ALTER TABLE customer_activity ADD CONSTRAINT customer_activity_kind_check CHECK (kind IN (
    'created', 'updated', 'archived', 'restored', 'note_deleted', 'history_deleted',
    'field_created', 'field_deleted', 'linked', 'unlinked', 'merged', 'separated',
    'user_linked', 'user_unlinked'
));

COMMIT;
