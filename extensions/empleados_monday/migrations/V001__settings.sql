BEGIN;

-- Token personal de la API de monday de la empresa, guardado una vez para que la pantalla Importar liste los tableros.
-- Se guarda sellado (enc:v1:...), asi que no lleva reglas de formato ni de largo.
CREATE TABLE ext_empleados_monday__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    token TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Una importacion de un tablero: se lee (reading), se revisa el resumen (preview), se aplica (applying) y termina (done).
-- pages = paginas leidas; retries = reintentos seguidos por errores temporales de monday; summary = resumen de la vista previa.
-- cursor: NULL = aun no se pidio la primera pagina; texto = cursor de la siguiente; '' = la lectura termino.
CREATE TABLE ext_empleados_monday__jobs (
    id UUID PRIMARY KEY,
    board_id VARCHAR(20) NOT NULL,
    board_name VARCHAR(200) NOT NULL DEFAULT '',
    status VARCHAR(12) NOT NULL DEFAULT 'reading'
        CHECK (status IN ('reading', 'preview', 'applying', 'done', 'failed', 'cancelled')),
    cursor TEXT,
    pages INTEGER NOT NULL DEFAULT 0,
    retries INTEGER NOT NULL DEFAULT 0,
    summary JSONB,
    mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    total INTEGER NOT NULL DEFAULT 0,
    applied INTEGER NOT NULL DEFAULT 0,
    created INTEGER NOT NULL DEFAULT 0,
    updated INTEGER NOT NULL DEFAULT 0,
    skipped INTEGER NOT NULL DEFAULT 0,
    failed INTEGER NOT NULL DEFAULT 0,
    message TEXT NOT NULL DEFAULT '',
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ext_empleados_monday__jobs_status_idx ON ext_empleados_monday__jobs (status, created_at);

-- Una sola importacion abierta (leyendo, en revision o aplicando) por empresa.
CREATE UNIQUE INDEX ext_empleados_monday__jobs_open_idx ON ext_empleados_monday__jobs ((1))
    WHERE status IN ('reading', 'preview', 'applying');

-- Una fila por elemento del tablero ya convertido (payload = empleado importado).
-- outcome queda vacio hasta aplicar la fila; message = motivo de una omision, un error o un aviso (horario no entendido).
CREATE TABLE ext_empleados_monday__rows (
    job_id UUID NOT NULL REFERENCES ext_empleados_monday__jobs (id) ON DELETE CASCADE,
    row_no INTEGER NOT NULL,
    item_id VARCHAR(20) NOT NULL,
    payload JSONB NOT NULL,
    outcome VARCHAR(10) CHECK (outcome IN ('created', 'updated', 'skipped', 'failed')),
    message TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (job_id, row_no)
);

CREATE UNIQUE INDEX ext_empleados_monday__rows_item_idx ON ext_empleados_monday__rows (job_id, item_id);

COMMIT;
