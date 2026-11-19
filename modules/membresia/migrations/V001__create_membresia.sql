BEGIN;

-- Ajustes del módulo: una sola fila por empresa.
CREATE TABLE membresia_settings (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    -- Días antes del vencimiento en que se avisa al equipo.
    expiring_days INT NOT NULL DEFAULT 7 CHECK (expiring_days BETWEEN 1 AND 60),
    -- Opciones de "cómo nos conoció".
    sources TEXT[] NOT NULL DEFAULT '{Referido,Walk-in,Sitio web,Otro}',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO membresia_settings (id) VALUES (1);

-- Niveles o planes de membresía. Un plan con membresías no se borra: se desactiva.
CREATE TABLE membresia_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL CHECK (length(trim(name)) > 0),
    description TEXT NOT NULL DEFAULT '',
    price NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    duration_days INT NOT NULL CHECK (duration_days BETWEEN 1 AND 3660),
    -- Visitas incluidas por período; NULL = ilimitadas.
    included_visits INT CHECK (included_visits >= 1),
    guest_passes INT NOT NULL DEFAULT 0 CHECK (guest_passes BETWEEN 0 AND 1000),
    wifi_access BOOLEAN NOT NULL DEFAULT true,
    active BOOLEAN NOT NULL DEFAULT true,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX membresia_plans_name_idx ON membresia_plans (lower(name));

-- Cada período de un miembro es una fila; renovar crea otra fila enlazada con renewed_from.
-- Los datos del plan (nombre, visitas, pases) se copian para que cambiar el plan no altere lo ya vendido.
CREATE TABLE membresia_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    plan_id UUID NOT NULL REFERENCES membresia_plans(id) ON DELETE RESTRICT,
    plan_name VARCHAR(80) NOT NULL,
    starts_on DATE NOT NULL,
    -- Inclusive: el último día de la membresía.
    ends_on DATE NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('programada', 'activa', 'vencida', 'cancelada')),
    source VARCHAR(40) NOT NULL DEFAULT '',
    host_user_id UUID,
    host_name VARCHAR(160) NOT NULL DEFAULT '',
    unit VARCHAR(80) NOT NULL DEFAULT '',
    notes TEXT NOT NULL DEFAULT '',
    included_visits INT CHECK (included_visits >= 1),
    visits_used INT NOT NULL DEFAULT 0 CHECK (visits_used >= 0),
    guest_passes_total INT NOT NULL DEFAULT 0 CHECK (guest_passes_total >= 0),
    guest_passes_used INT NOT NULL DEFAULT 0 CHECK (guest_passes_used >= 0),
    renewed_from UUID REFERENCES membresia_memberships(id) ON DELETE SET NULL,
    cancelled_at TIMESTAMPTZ,
    cancel_reason TEXT NOT NULL DEFAULT '',
    -- Día en que se avisó al equipo del vencimiento (una sola vez por membresía).
    expiring_notified_on DATE,
    created_by VARCHAR(160) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT membresia_memberships_dates CHECK (ends_on >= starts_on),
    CONSTRAINT membresia_memberships_guest_passes CHECK (guest_passes_used <= guest_passes_total),
    CONSTRAINT membresia_memberships_cancelled CHECK ((status = 'cancelada') = (cancelled_at IS NOT NULL))
);

CREATE INDEX membresia_memberships_customer_idx ON membresia_memberships (customer_id, ends_on DESC);
CREATE INDEX membresia_memberships_status_idx ON membresia_memberships (status, ends_on);
CREATE INDEX membresia_memberships_plan_idx ON membresia_memberships (plan_id);
CREATE INDEX membresia_memberships_ends_idx ON membresia_memberships (ends_on, id);
-- Una membresia solo se renueva una vez (mientras la renovacion no este cancelada).
CREATE UNIQUE INDEX membresia_memberships_renewed_idx ON membresia_memberships (renewed_from)
    WHERE renewed_from IS NOT NULL AND status <> 'cancelada';

-- Entradas al espacio: del miembro o de un invitado con el pase del miembro.
CREATE TABLE membresia_visits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    membership_id UUID NOT NULL REFERENCES membresia_memberships(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('miembro', 'invitado')),
    guest_name VARCHAR(160),
    visit_on DATE NOT NULL,
    visited_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- true cuando recepción registró la visita aunque el plan estaba agotado o vencido.
    override BOOLEAN NOT NULL DEFAULT false,
    recorded_by VARCHAR(160) NOT NULL DEFAULT '',
    note VARCHAR(500) NOT NULL DEFAULT '',

    CONSTRAINT membresia_visits_guest CHECK (
        (kind = 'miembro' AND guest_name IS NULL)
        OR (kind = 'invitado' AND length(trim(guest_name)) > 0)
    )
);

-- Una visita de miembro por día: un doble toque no cuenta dos veces.
CREATE UNIQUE INDEX membresia_visits_daily_idx ON membresia_visits (membership_id, visit_on) WHERE kind = 'miembro';
CREATE INDEX membresia_visits_day_idx ON membresia_visits (visit_on);
CREATE INDEX membresia_visits_membership_idx ON membresia_visits (membership_id, visited_at DESC);
CREATE INDEX membresia_visits_customer_idx ON membresia_visits (customer_id, visited_at DESC);

-- Contrato para otros modulos (por ejemplo eventos): quien tiene hoy una membresia vigente.
-- CURRENT_DATE es la fecha de la sesion de la base de datos (su zona horaria), no la del navegador.
-- Un modulo la usa solo si existe: to_regclass('public.membresia_active_members').
CREATE VIEW membresia_active_members AS
SELECT m.customer_id, m.id AS membership_id, m.plan_id, m.plan_name, m.starts_on, m.ends_on
FROM membresia_memberships m
JOIN customers c ON c.id = m.customer_id
WHERE m.status <> 'cancelada' AND c.status <> 'archived'
  AND CURRENT_DATE BETWEEN m.starts_on AND m.ends_on;

COMMIT;
