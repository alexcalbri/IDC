BEGIN;

-- Token personal de la API de monday de la empresa, guardado una vez para que la pantalla Importar liste los tableros.
-- Se guarda sellado (enc:v1:...), asi que no lleva reglas de formato ni de largo.
CREATE TABLE ext_mantenimiento_monday__settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    token TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Una importacion de un tablero: se lee (reading), se revisa el resumen (preview), se aplica (applying) y termina (done).
-- target = a que va el tablero: planes preventivos (plans) u ordenes y proyectos (orders); default_kind = tipo de las ordenes
-- (corrective o project). pages = paginas leidas; retries = reintentos seguidos por errores temporales de monday;
-- summary = resumen de la vista previa.
-- cursor: NULL = aun no se pidio la primera pagina; texto = cursor de la siguiente; '' = la lectura termino.
CREATE TABLE ext_mantenimiento_monday__jobs (
    id UUID PRIMARY KEY,
    board_id VARCHAR(20) NOT NULL,
    board_name VARCHAR(200) NOT NULL DEFAULT '',
    target VARCHAR(8) NOT NULL CHECK (target IN ('plans', 'orders')),
    default_kind VARCHAR(12) NOT NULL DEFAULT 'corrective' CHECK (default_kind IN ('corrective', 'project')),
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
    files_done INTEGER NOT NULL DEFAULT 0,
    files_failed INTEGER NOT NULL DEFAULT 0,
    message TEXT NOT NULL DEFAULT '',
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ext_mantenimiento_monday__jobs_status_idx ON ext_mantenimiento_monday__jobs (status, created_at);

-- Una sola importacion abierta (leyendo, en revision o aplicando) por empresa.
CREATE UNIQUE INDEX ext_mantenimiento_monday__jobs_open_idx ON ext_mantenimiento_monday__jobs ((1))
    WHERE status IN ('reading', 'preview', 'applying');

-- Una fila por elemento del tablero ya convertido (payload = plan u orden importada; files = archivos por descargar al aplicar).
-- outcome queda vacio hasta aplicar la fila; entity_id = orden creada o actualizada (los archivos solo van a ordenes);
-- files_pending = la orden ya se aplico y faltan archivos.
CREATE TABLE ext_mantenimiento_monday__rows (
    job_id UUID NOT NULL REFERENCES ext_mantenimiento_monday__jobs (id) ON DELETE CASCADE,
    row_no INTEGER NOT NULL,
    item_id VARCHAR(20) NOT NULL,
    payload JSONB NOT NULL,
    files JSONB NOT NULL DEFAULT '[]'::jsonb,
    entity_id UUID,
    files_pending BOOLEAN NOT NULL DEFAULT false,
    outcome VARCHAR(10) CHECK (outcome IN ('created', 'updated', 'skipped', 'failed')),
    message TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (job_id, row_no)
);

CREATE UNIQUE INDEX ext_mantenimiento_monday__rows_item_idx ON ext_mantenimiento_monday__rows (job_id, item_id);

-- Filas por trabajar (sin aplicar o con archivos por bajar): la consulta de pendientes del trabajo de importacion.
CREATE INDEX ext_mantenimiento_monday__rows_pending_idx ON ext_mantenimiento_monday__rows (job_id, row_no)
    WHERE outcome IS NULL OR files_pending;

-- Personas de monday (id -> nombre y correo) de una importacion, para emparejarlas con los usuarios de Mantenimiento.
CREATE TABLE ext_mantenimiento_monday__people (
    job_id UUID NOT NULL REFERENCES ext_mantenimiento_monday__jobs (id) ON DELETE CASCADE,
    monday_id VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL DEFAULT '',
    email VARCHAR(320) NOT NULL DEFAULT '',
    PRIMARY KEY (job_id, monday_id)
);

COMMIT;
