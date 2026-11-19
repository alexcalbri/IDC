BEGIN;

-- Una fila por empresa: moneda por defecto de las solicitudes nuevas.
CREATE TABLE compras_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    default_currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK (default_currency ~ '^[A-Z]{3}$'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO compras_settings (id) VALUES (1);

-- Departamentos o categorias de compra, configurables por empresa.
CREATE TABLE compras_departments (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX compras_departments_name_idx ON compras_departments (lower(name));

INSERT INTO compras_departments (id, name, sort_order) VALUES
    (gen_random_uuid(), 'Hotel', 0),
    (gen_random_uuid(), 'Alimentos y bebidas', 1),
    (gen_random_uuid(), 'Mantenimiento', 2),
    (gen_random_uuid(), 'Administracion', 3),
    (gen_random_uuid(), 'IT', 4);

-- Aprobadores designados. max_amount NULL = sin limite; el limite aplica en limit_currency.
-- Sin clave foranea: application_users es del Core; el codigo valida que el usuario exista.
CREATE TABLE compras_approvers (
    user_id UUID PRIMARY KEY,
    max_amount NUMERIC(14, 2) CHECK (max_amount IS NULL OR max_amount >= 0),
    limit_currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK (limit_currency ~ '^[A-Z]{3}$'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by TEXT
);

-- Solicitudes de compra. number es el folio visible (C-00012).
-- source_kind/source_ref: origen opcional (p. ej. una orden de mantenimiento); una solicitud por origen.
CREATE TABLE compras_requests (
    id UUID PRIMARY KEY,
    number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 200),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
    department_id UUID REFERENCES compras_departments(id) ON DELETE SET NULL,
    estimated_amount NUMERIC(14, 2) CHECK (estimated_amount IS NULL OR estimated_amount >= 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    required_date DATE,
    priority TEXT NOT NULL DEFAULT 'medium' CHECK (priority IN ('low', 'medium', 'high', 'critical')),
    status TEXT NOT NULL DEFAULT 'draft' CHECK (
        status IN ('draft', 'quoting', 'pending_approval', 'approved', 'rejected', 'in_progress', 'completed', 'cancelled')
    ),
    requester_id UUID NOT NULL,
    requester_name TEXT NOT NULL,
    decided_by UUID,
    decided_by_name TEXT,
    decided_at TIMESTAMPTZ,
    decision_reason TEXT,
    approved_amount NUMERIC(14, 2) CHECK (approved_amount IS NULL OR approved_amount >= 0),
    approved_currency CHAR(3) CHECK (approved_currency IS NULL OR approved_currency ~ '^[A-Z]{3}$'),
    source_kind TEXT,
    source_ref TEXT,
    submitted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((source_kind IS NULL) = (source_ref IS NULL)),
    CHECK ((approved_amount IS NULL) = (approved_currency IS NULL)),
    -- Aprobada, rechazada y en progreso siempre tienen quien decidio (completada y cancelada pueden venir de borrador o aprobada).
    CHECK (status NOT IN ('approved', 'rejected', 'in_progress') OR decided_by IS NOT NULL),
    UNIQUE (source_kind, source_ref)
);

CREATE INDEX compras_requests_status_idx ON compras_requests (status, created_at DESC);
CREATE INDEX compras_requests_requester_idx ON compras_requests (requester_id, created_at DESC);
CREATE INDEX compras_requests_department_idx ON compras_requests (department_id);
CREATE INDEX compras_requests_priority_idx ON compras_requests (priority)
    WHERE status NOT IN ('completed', 'cancelled', 'rejected');
CREATE INDEX compras_requests_created_idx ON compras_requests (created_at);
CREATE INDEX compras_requests_decided_by_idx ON compras_requests (decided_by) WHERE decided_by IS NOT NULL;
CREATE INDEX compras_requests_status_decided_idx ON compras_requests (status, (COALESCE(decided_at, created_at)));

-- Cotizaciones de una solicitud; solo una puede estar elegida.
CREATE TABLE compras_quotes (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES compras_requests(id) ON DELETE CASCADE,
    supplier TEXT NOT NULL CHECK (length(trim(supplier)) BETWEEN 1 AND 200),
    amount NUMERIC(14, 2) NOT NULL CHECK (amount >= 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    notes TEXT NOT NULL DEFAULT '',
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    quoted_by UUID,
    quoted_by_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX compras_quotes_request_idx ON compras_quotes (request_id);
CREATE UNIQUE INDEX compras_quotes_selected_idx ON compras_quotes (request_id) WHERE selected;

-- Respaldos (facturas, proformas, cotizaciones...). El contenido vive en el almacen de archivos de la
-- empresa. request_id NULL: subida que espera su formulario o huerfana de una solicitud borrada; el
-- trabajo periodico la limpia. Que quote_id pertenezca a la misma solicitud se valida en codigo (link): una FK
-- compuesta (quote_id, request_id) con ON DELETE SET NULL anularia tambien request_id y dejaria el archivo huerfano. uploaded_by_id permite que solo quien subio un archivo pendiente lo vincule.
CREATE TABLE compras_attachments (
    id UUID PRIMARY KEY,
    request_id UUID REFERENCES compras_requests(id) ON DELETE SET NULL,
    quote_id UUID REFERENCES compras_quotes(id) ON DELETE SET NULL,
    kind TEXT NOT NULL DEFAULT 'support' CHECK (kind IN ('support', 'quote', 'proforma', 'invoice', 'other')),
    file_name TEXT NOT NULL CHECK (length(file_name) BETWEEN 1 AND 255),
    folder_path TEXT NOT NULL DEFAULT '',
    content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    storage_backend TEXT NOT NULL,
    storage_ref TEXT NOT NULL,
    uploaded_by TEXT NOT NULL DEFAULT '',
    uploaded_by_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX compras_attachments_request_idx ON compras_attachments (request_id) WHERE deleted_at IS NULL;
CREATE INDEX compras_attachments_quote_idx ON compras_attachments (quote_id);
CREATE INDEX compras_attachments_cleanup_idx ON compras_attachments (created_at) WHERE deleted_at IS NOT NULL OR request_id IS NULL;
CREATE INDEX compras_attachments_pending_idx ON compras_attachments (uploaded_by_id) WHERE request_id IS NULL AND deleted_at IS NULL;

-- Historial de cada solicitud.
CREATE TABLE compras_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES compras_requests(id) ON DELETE CASCADE,
    at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id UUID,
    actor_name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('created', 'edited', 'status', 'quote', 'attachment')),
    from_status TEXT CHECK (from_status IS NULL OR from_status IN ('draft', 'quoting', 'pending_approval', 'approved', 'rejected', 'in_progress', 'completed', 'cancelled')),
    to_status TEXT CHECK (to_status IS NULL OR to_status IN ('draft', 'quoting', 'pending_approval', 'approved', 'rejected', 'in_progress', 'completed', 'cancelled')),
    detail TEXT NOT NULL DEFAULT ''
);

CREATE INDEX compras_events_request_idx ON compras_events (request_id, at DESC);

COMMIT;
