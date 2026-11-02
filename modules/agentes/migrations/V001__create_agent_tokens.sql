BEGIN;

-- Tokens with which a user connects an AI agent (MCP client). Only the SHA-256 of the token is
-- stored. user_id is a tenant user, or the central user id of a server owner (server_owner = TRUE),
-- so it has no foreign key; the endpoint checks that the user is still active on every request.
CREATE TABLE agent_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    server_owner BOOLEAN NOT NULL DEFAULT FALSE,
    user_name TEXT NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    -- First characters of the secret, to recognize the token in lists.
    token_hint VARCHAR(12) NOT NULL,
    -- Modules the agent may use; NULL = every module the user can use.
    allowed_modules TEXT[],
    read_only BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by TEXT,
    -- Connections made through OAuth (claude.ai...): client id (name goes in name), current refresh token and the
    -- expiration of the current access token. expires_at is then the end of the whole connection.
    oauth_client TEXT,
    refresh_hash VARCHAR(64) UNIQUE,
    access_expires_at TIMESTAMPTZ
);

CREATE INDEX agent_tokens_user_idx ON agent_tokens (user_id, created_at DESC);

-- What agents did: every tool call, allowed or not.
CREATE TABLE agent_activity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_id UUID NOT NULL REFERENCES agent_tokens(id) ON DELETE CASCADE,
    tool TEXT NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('ok', 'error', 'denied')),
    detail TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX agent_activity_token_idx ON agent_activity (token_id, created_at DESC);
CREATE INDEX agent_activity_created_idx ON agent_activity (created_at);

COMMIT;
