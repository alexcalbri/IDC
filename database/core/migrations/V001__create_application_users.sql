BEGIN;

CREATE TABLE application_users (
                                   id UUID NOT NULL,
                                   postgres_role VARCHAR(63) NOT NULL,
                                   is_active BOOLEAN NOT NULL DEFAULT FALSE,

                                   CONSTRAINT application_users_pkey
                                       PRIMARY KEY (id),

                                   CONSTRAINT application_users_postgres_role_unique
                                       UNIQUE (postgres_role)
);

COMMIT;