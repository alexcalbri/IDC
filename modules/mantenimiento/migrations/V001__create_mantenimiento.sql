BEGIN;

-- Propiedades (sedes, edificios, casas...): nombre libre, sin repetir entre las activas.
CREATE TABLE mantenimiento_properties (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (btrim(name) <> ''),
    position INTEGER NOT NULL DEFAULT 0,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX mantenimiento_properties_name_idx ON mantenimiento_properties (lower(name)) WHERE NOT archived;

-- Activos (equipos, instalaciones) que pertenecen a una propiedad.
CREATE TABLE mantenimiento_assets (
    id UUID PRIMARY KEY,
    property_id UUID NULL REFERENCES mantenimiento_properties (id) ON DELETE SET NULL,
    name VARCHAR(160) NOT NULL CHECK (btrim(name) <> ''),
    category VARCHAR(60) NOT NULL DEFAULT '',
    location VARCHAR(160) NOT NULL DEFAULT '',
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 5000),
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX mantenimiento_assets_property_idx ON mantenimiento_assets (property_id) WHERE NOT archived;
-- Un nombre de activo no se repite dentro de la misma propiedad (ni entre los activos sin propiedad): protege a las importaciones simultáneas.
CREATE UNIQUE INDEX mantenimiento_assets_name_idx ON mantenimiento_assets (COALESCE(property_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name)) WHERE NOT archived;

-- Planes preventivos: generan una orden cuando se acerca next_due (ver el trabajo plan-generator).
CREATE TABLE mantenimiento_plans (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL CHECK (btrim(title) <> ''),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
    property_id UUID NULL REFERENCES mantenimiento_properties (id) ON DELETE SET NULL,
    asset_id UUID NULL REFERENCES mantenimiento_assets (id) ON DELETE SET NULL,
    frequency VARCHAR(16) NOT NULL CHECK (frequency IN ('monthly', 'quarterly', 'yearly', 'every_n_days')),
    interval_days INTEGER NULL CHECK (interval_days BETWEEN 1 AND 3650),
    schedule_mode VARCHAR(8) NOT NULL DEFAULT 'fixed' CHECK (schedule_mode IN ('fixed', 'floating')),
    next_due DATE NULL,
    -- Fecha fija desde la que se cuentan las ocurrencias del calendario fijo (así el fin de mes no se arrastra).
    anchor_date DATE NULL,
    -- Último intento fallido del generador: el plan se excluye del lote unas horas.
    last_error_at TIMESTAMPTZ NULL,
    lead_days INTEGER NOT NULL DEFAULT 7 CHECK (lead_days BETWEEN 0 AND 90),
    default_assignee UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    priority VARCHAR(8) NOT NULL DEFAULT 'medium' CHECK (priority IN ('urgent', 'high', 'medium', 'low')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    import_key VARCHAR(200) NULL,
    created_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mantenimiento_plans_interval_check CHECK ((frequency = 'every_n_days') = (interval_days IS NOT NULL))
);

CREATE INDEX mantenimiento_plans_due_idx ON mantenimiento_plans (next_due) WHERE active;
CREATE INDEX mantenimiento_plans_asset_idx ON mantenimiento_plans (asset_id);
CREATE INDEX mantenimiento_plans_property_idx ON mantenimiento_plans (property_id);
CREATE INDEX mantenimiento_plans_assignee_idx ON mantenimiento_plans (default_assignee) WHERE default_assignee IS NOT NULL;
CREATE UNIQUE INDEX mantenimiento_plans_import_idx ON mantenimiento_plans (import_key) WHERE import_key IS NOT NULL;

CREATE TABLE mantenimiento_plan_checklist (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES mantenimiento_plans (id) ON DELETE CASCADE,
    text VARCHAR(300) NOT NULL CHECK (btrim(text) <> ''),
    position INTEGER NOT NULL DEFAULT 0,
    import_key VARCHAR(200) NULL
);

CREATE INDEX mantenimiento_plan_checklist_plan_idx ON mantenimiento_plan_checklist (plan_id, position);
CREATE UNIQUE INDEX mantenimiento_plan_checklist_import_idx ON mantenimiento_plan_checklist (plan_id, import_key) WHERE import_key IS NOT NULL;

-- Órdenes de trabajo. number es el folio visible (OT-0007). Una columna de identidad
-- da el permiso de la secuencia al rol runtime junto con el de la tabla (igual que Compras).
CREATE TABLE mantenimiento_orders (
    id UUID PRIMARY KEY,
    number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('preventive', 'corrective', 'project')),
    status VARCHAR(12) NOT NULL CHECK (status IN ('requested', 'quoting', 'open', 'in_progress', 'on_hold', 'done', 'rejected', 'cancelled')),
    priority VARCHAR(8) NOT NULL DEFAULT 'medium' CHECK (priority IN ('urgent', 'high', 'medium', 'low')),
    title VARCHAR(200) NOT NULL CHECK (btrim(title) <> ''),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 20000),
    property_id UUID NULL REFERENCES mantenimiento_properties (id) ON DELETE SET NULL,
    asset_id UUID NULL REFERENCES mantenimiento_assets (id) ON DELETE SET NULL,
    plan_id UUID NULL REFERENCES mantenimiento_plans (id) ON DELETE SET NULL,
    plan_due DATE NULL,
    due_date DATE NULL,
    assignee_id UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    requested_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    requested_by_name VARCHAR(120) NOT NULL DEFAULT '',
    department VARCHAR(80) NOT NULL DEFAULT '',
    quote_amount NUMERIC(14, 2) NULL CHECK (quote_amount >= 0),
    quote_currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK (quote_currency ~ '^[A-Z]{3}$'),
    quoted_by VARCHAR(120) NOT NULL DEFAULT '',
    cost_amount NUMERIC(14, 2) NULL CHECK (cost_amount >= 0),
    cost_currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK (cost_currency ~ '^[A-Z]{3}$'),
    resolution TEXT NOT NULL DEFAULT '' CHECK (length(resolution) <= 5000),
    rejected_reason VARCHAR(1000) NOT NULL DEFAULT '',
    approved_at TIMESTAMPTZ NULL,
    approved_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    completed_at TIMESTAMPTZ NULL,
    completed_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    -- Integración opcional con Tareas: la orden asignada se refleja en una tarea (trabajo tareas-sync).
    tareas_task_id UUID NULL,
    tareas_sync_needed BOOLEAN NOT NULL DEFAULT FALSE,
    -- Integración opcional con Compras: la reserva evita dos solicitudes a la vez (purchase_linking_at).
    purchase_request_id UUID NULL,
    purchase_folio VARCHAR(40) NOT NULL DEFAULT '',
    purchase_linking_at TIMESTAMPTZ NULL,
    import_key VARCHAR(200) NULL,
    created_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ NULL,
    CONSTRAINT mantenimiento_orders_done_check CHECK ((status = 'done') = (completed_at IS NOT NULL))
);

-- El generador de planes no duplica: una orden por plan y fecha de vencimiento.
CREATE UNIQUE INDEX mantenimiento_orders_plan_due_idx ON mantenimiento_orders (plan_id, plan_due) WHERE plan_id IS NOT NULL AND plan_due IS NOT NULL;
CREATE UNIQUE INDEX mantenimiento_orders_import_idx ON mantenimiento_orders (import_key) WHERE import_key IS NOT NULL;
CREATE INDEX mantenimiento_orders_status_due_idx ON mantenimiento_orders (status, due_date) WHERE archived_at IS NULL;
CREATE INDEX mantenimiento_orders_assignee_idx ON mantenimiento_orders (assignee_id) WHERE archived_at IS NULL;
CREATE INDEX mantenimiento_orders_asset_idx ON mantenimiento_orders (asset_id, created_at DESC);
CREATE INDEX mantenimiento_orders_requested_idx ON mantenimiento_orders (requested_by);
-- Una sola orden abierta por plan: lo garantiza la base de datos, no solo el generador.
CREATE UNIQUE INDEX mantenimiento_orders_open_plan_idx ON mantenimiento_orders (plan_id) WHERE plan_id IS NOT NULL AND status NOT IN ('done', 'rejected', 'cancelled');
CREATE INDEX mantenimiento_orders_sync_idx ON mantenimiento_orders (updated_at, id) WHERE tareas_sync_needed AND archived_at IS NULL;
CREATE INDEX mantenimiento_orders_property_idx ON mantenimiento_orders (property_id) WHERE archived_at IS NULL;
CREATE INDEX mantenimiento_orders_upcoming_idx ON mantenimiento_orders (due_date, number DESC) WHERE archived_at IS NULL AND status NOT IN ('done', 'rejected', 'cancelled');

CREATE TABLE mantenimiento_order_checklist (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES mantenimiento_orders (id) ON DELETE CASCADE,
    text VARCHAR(300) NOT NULL CHECK (btrim(text) <> ''),
    done BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL DEFAULT 0,
    done_by UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    done_at TIMESTAMPTZ NULL,
    import_key VARCHAR(200) NULL
);

CREATE INDEX mantenimiento_order_checklist_order_idx ON mantenimiento_order_checklist (order_id, position);
CREATE UNIQUE INDEX mantenimiento_order_checklist_import_idx ON mantenimiento_order_checklist (order_id, import_key) WHERE import_key IS NOT NULL;

-- Notas e historial de la orden: kind distingue la nota escrita del cambio de estado, la asignación y lo del sistema.
CREATE TABLE mantenimiento_order_notes (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES mantenimiento_orders (id) ON DELETE CASCADE,
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('note', 'status', 'assign', 'system')),
    body TEXT NOT NULL CHECK (btrim(body) <> '' AND length(body) <= 5000),
    author_id UUID NULL REFERENCES application_users (id) ON DELETE SET NULL,
    author_name VARCHAR(120) NOT NULL DEFAULT '',
    import_key VARCHAR(200) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX mantenimiento_order_notes_order_idx ON mantenimiento_order_notes (order_id, created_at DESC);
CREATE UNIQUE INDEX mantenimiento_order_notes_import_idx ON mantenimiento_order_notes (order_id, import_key) WHERE import_key IS NOT NULL;

-- Los archivos viven en el almacenamiento de la empresa (backend + ref). order_id es NULL hasta que se guarda
-- el formulario que los subió. Las filas delete_pending esperan al trabajo de limpieza.
CREATE TABLE mantenimiento_attachments (
    id UUID PRIMARY KEY,
    order_id UUID NULL REFERENCES mantenimiento_orders (id) ON DELETE SET NULL,
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

CREATE INDEX mantenimiento_attachments_order_idx ON mantenimiento_attachments (order_id);
CREATE INDEX mantenimiento_attachments_cleanup_idx ON mantenimiento_attachments (created_at) WHERE order_id IS NULL OR delete_pending;
CREATE UNIQUE INDEX mantenimiento_attachments_import_idx ON mantenimiento_attachments (order_id, import_key) WHERE import_key IS NOT NULL;

-- Avisos de vencimiento ya enviados: uno por orden, usuario, tipo y fecha.
CREATE TABLE mantenimiento_reminders (
    order_id UUID NOT NULL REFERENCES mantenimiento_orders (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES application_users (id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('due_soon', 'overdue')),
    due_date DATE NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, user_id, kind, due_date)
);

CREATE INDEX mantenimiento_reminders_user_idx ON mantenimiento_reminders (user_id);

-- Ajustes de la empresa (una sola fila).
CREATE TABLE mantenimiento_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    tareas_board_id UUID NULL,
    tareas_publisher_id UUID NULL,
    due_soon_days INTEGER NOT NULL DEFAULT 1 CHECK (due_soon_days BETWEEN 0 AND 30),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO mantenimiento_settings (id) VALUES (1);

COMMIT;
