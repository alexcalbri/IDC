BEGIN;

CREATE TABLE server_owners (
    user_id UUID PRIMARY KEY REFERENCES application_users(id)
);

CREATE TABLE companies (
    id UUID PRIMARY KEY,
    code VARCHAR(63) NOT NULL UNIQUE CHECK (code ~ '^[a-z][a-z0-9_]{0,62}$'),
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    database_name VARCHAR(63) NOT NULL UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

COMMIT;
