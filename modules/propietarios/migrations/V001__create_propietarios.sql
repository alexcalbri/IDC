BEGIN;

-- Desarrollos (urbanizaciones, proyectos). Se archivan, no se borran. import_key guarda la clave que traia de otro
-- sistema (por ejemplo name:<nombre en minusculas>) para que importar de nuevo no duplique.
CREATE TABLE propietarios_developments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL CHECK (btrim(name) <> ''),
    location VARCHAR(300) NOT NULL DEFAULT '',
    map_url VARCHAR(1000) NOT NULL DEFAULT '',
    latitude NUMERIC(9, 6) NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude NUMERIC(9, 6) NULL CHECK (longitude BETWEEN -180 AND 180),
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 10000),
    archived_at TIMESTAMPTZ NULL,
    created_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    import_key VARCHAR(200) NULL
);

CREATE UNIQUE INDEX propietarios_developments_import_key_idx ON propietarios_developments (import_key) WHERE import_key IS NOT NULL;
CREATE UNIQUE INDEX propietarios_developments_name_idx ON propietarios_developments (lower(name)) WHERE archived_at IS NULL;

-- Lotes de un desarrollo. El estado es una lista fija. Un numero de lote no se repite dentro de su desarrollo mientras el lote no este archivado.
CREATE TABLE propietarios_lots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    development_id UUID NOT NULL REFERENCES propietarios_developments (id) ON DELETE RESTRICT,
    lot_number VARCHAR(60) NOT NULL CHECK (btrim(lot_number) <> ''),
    register_number VARCHAR(80) NOT NULL DEFAULT '',
    property_name VARCHAR(200) NOT NULL DEFAULT '',
    property_type VARCHAR(80) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'disponible' CHECK (status IN ('disponible', 'reservado', 'vendido', 'en_construccion', 'construido')),
    area_m2 NUMERIC(14, 2) NULL CHECK (area_m2 >= 0),
    latitude NUMERIC(9, 6) NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude NUMERIC(9, 6) NULL CHECK (longitude BETWEEN -180 AND 180),
    map_url VARCHAR(1000) NOT NULL DEFAULT '',
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 10000),
    archived_at TIMESTAMPTZ NULL,
    created_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    import_key VARCHAR(200) NULL
);

CREATE UNIQUE INDEX propietarios_lots_number_idx ON propietarios_lots (development_id, lower(lot_number)) WHERE archived_at IS NULL;
CREATE UNIQUE INDEX propietarios_lots_import_key_idx ON propietarios_lots (import_key) WHERE import_key IS NOT NULL;
CREATE INDEX propietarios_lots_register_idx ON propietarios_lots (lower(register_number)) WHERE register_number <> '';
CREATE INDEX propietarios_lots_lot_number_idx ON propietarios_lots (lower(lot_number));
CREATE INDEX propietarios_lots_development_status_idx ON propietarios_lots (development_id, status) WHERE archived_at IS NULL;

-- Titularidades: quien es dueño de un lote y desde/hasta cuando. El titular es un cliente (Clientes guarda sus datos de contacto).
-- Vigente = ends_on IS NULL o ends_on posterior a hoy. share_percent es opcional: muchos registros no lo tienen.
CREATE TABLE propietarios_ownerships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lot_id UUID NOT NULL REFERENCES propietarios_lots (id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    share_percent NUMERIC(5, 2) NULL CHECK (share_percent > 0 AND share_percent <= 100),
    starts_on DATE NULL,
    ends_on DATE NULL,
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 5000),
    created_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ NULL,
    import_key VARCHAR(200) NULL,
    CONSTRAINT propietarios_ownerships_dates_check CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on)
);

CREATE INDEX propietarios_ownerships_open_idx ON propietarios_ownerships (lot_id) WHERE ends_on IS NULL;
CREATE INDEX propietarios_ownerships_lot_idx ON propietarios_ownerships (lot_id);
CREATE INDEX propietarios_ownerships_customer_idx ON propietarios_ownerships (customer_id);
-- Un cliente no puede tener dos titularidades abiertas del mismo lote.
CREATE UNIQUE INDEX propietarios_ownerships_open_customer_idx ON propietarios_ownerships (lot_id, customer_id) WHERE ends_on IS NULL;
CREATE UNIQUE INDEX propietarios_ownerships_import_key_idx ON propietarios_ownerships (lot_id, import_key) WHERE import_key IS NOT NULL;

-- Historial de cada lote: altas, traspasos, bajas, ediciones e importaciones.
CREATE TABLE propietarios_lot_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lot_id UUID NOT NULL REFERENCES propietarios_lots (id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('alta', 'traspaso', 'baja', 'edicion', 'importacion')),
    happened_on DATE NOT NULL DEFAULT CURRENT_DATE,
    detail TEXT NOT NULL DEFAULT '' CHECK (length(detail) <= 5000),
    author VARCHAR(160) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX propietarios_lot_history_lot_idx ON propietarios_lot_history (lot_id, created_at DESC);

-- Archivos (escrituras, planos) en el almacenamiento de archivos de la empresa (backend + ref). lot_id es NULL hasta que se guarda el
-- formulario que subio el archivo; las filas con delete_pending las borra el trabajo de limpieza.
CREATE TABLE propietarios_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lot_id UUID NULL REFERENCES propietarios_lots (id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    path VARCHAR(1000) NOT NULL DEFAULT '',
    content_type VARCHAR(200) NOT NULL DEFAULT 'application/octet-stream',
    size BIGINT NOT NULL DEFAULT 0,
    backend VARCHAR(40) NOT NULL,
    ref VARCHAR(500) NOT NULL,
    uploaded_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delete_pending BOOLEAN NOT NULL DEFAULT FALSE,
    import_key VARCHAR(200) NULL
);

CREATE INDEX propietarios_attachments_lot_idx ON propietarios_attachments (lot_id);
CREATE INDEX propietarios_attachments_cleanup_idx ON propietarios_attachments (created_at) WHERE lot_id IS NULL OR delete_pending;
CREATE UNIQUE INDEX propietarios_attachments_import_key_idx ON propietarios_attachments (lot_id, import_key) WHERE import_key IS NOT NULL;

COMMIT;
