BEGIN;

-- Token personal de la API de monday de la empresa, guardado una vez para que la pantalla de importar liste los tableros
-- a elegir. Se guarda sellado (enc:v1:...), asi que no lleva reglas de formato ni de largo.
CREATE TABLE ext_monday__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    token TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
