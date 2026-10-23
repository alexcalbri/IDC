BEGIN;

CREATE TABLE application_sessions (
                                      token_hash VARCHAR(64) NOT NULL,
                                      user_id UUID NOT NULL,
                                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      expires_at TIMESTAMPTZ NOT NULL,
                                      revoked_at TIMESTAMPTZ,

                                      CONSTRAINT application_sessions_pkey
                                          PRIMARY KEY (token_hash),

                                      CONSTRAINT application_sessions_user_fk
                                          FOREIGN KEY (user_id)
                                              REFERENCES application_users (id)
                                              ON DELETE CASCADE,

                                      CONSTRAINT application_sessions_expiration_check
                                          CHECK (expires_at > created_at)
);

CREATE INDEX application_sessions_user_id_idx
    ON application_sessions (user_id);

COMMIT;