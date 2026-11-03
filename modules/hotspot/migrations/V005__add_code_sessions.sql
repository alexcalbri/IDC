BEGIN;

-- Access by customer or user code is one shared session per code: the first device starts it with
-- the network's access time, the next devices join it for the time left, and when it expires every
-- device that used the code is disconnected together (job "code-sessions").
CREATE TABLE hotspot_code_sessions (
    id UUID PRIMARY KEY,
    customer_id UUID REFERENCES customers(id) ON DELETE CASCADE,
    user_id UUID,
    user_company VARCHAR(63),
    pass_id UUID REFERENCES hotspot_access_passes(id) ON DELETE SET NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    -- Set when the devices were disconnected; NULL while the session is open.
    ended_at TIMESTAMPTZ,

    CONSTRAINT hotspot_code_sessions_owner CHECK ((customer_id IS NULL) <> (user_id IS NULL)),
    CONSTRAINT hotspot_code_sessions_window CHECK (expires_at > started_at)
);

CREATE INDEX hotspot_code_sessions_open_idx ON hotspot_code_sessions (expires_at) WHERE ended_at IS NULL;
CREATE INDEX hotspot_code_sessions_customer_idx ON hotspot_code_sessions (customer_id) WHERE ended_at IS NULL;
CREATE INDEX hotspot_code_sessions_user_idx ON hotspot_code_sessions (user_company, user_id) WHERE ended_at IS NULL;

-- Which session each device used. joined = the device entered an open session: it got the time left,
-- spent no balance and does not move the reconnection wait.
ALTER TABLE hotspot_accesses
    ADD COLUMN session_id UUID REFERENCES hotspot_code_sessions(id) ON DELETE SET NULL,
    ADD COLUMN joined BOOLEAN NOT NULL DEFAULT FALSE,
    -- When the device was disconnected because its session expired.
    ADD COLUMN disconnected_at TIMESTAMPTZ;

CREATE INDEX hotspot_accesses_session_idx ON hotspot_accesses (session_id) WHERE session_id IS NOT NULL;

COMMIT;
