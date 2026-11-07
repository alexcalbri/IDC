BEGIN;

-- Messenger and Instagram reply with the page token read from the system user token (shared_tokens),
-- or each with a token of its own. With its own token, Instagram uses the account id typed here.
ALTER TABLE crm_meta_settings
    ADD COLUMN IF NOT EXISTS shared_tokens BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS messenger_token TEXT,
    ADD COLUMN IF NOT EXISTS instagram_token TEXT,
    -- @username of the Instagram account linked to the page, shown in the settings.
    ADD COLUMN IF NOT EXISTS instagram_username TEXT;

COMMIT;
