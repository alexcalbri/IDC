BEGIN;

-- Correo saliente (SMTP) de la empresa. Una sola fila; la contrasena se guarda sellada (enc:v1:...) y nunca en claro.
CREATE TABLE IF NOT EXISTS company_mail_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    host VARCHAR(255) NOT NULL DEFAULT '',
    port INTEGER NOT NULL DEFAULT 587 CHECK (port BETWEEN 1 AND 65535),
    username VARCHAR(255) NOT NULL DEFAULT '',
    password_enc TEXT,
    from_address VARCHAR(255) NOT NULL DEFAULT '',
    security VARCHAR(16) NOT NULL DEFAULT 'starttls' CHECK (security IN ('starttls', 'ssl', 'none')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
