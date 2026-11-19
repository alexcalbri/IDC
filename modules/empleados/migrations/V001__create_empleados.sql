BEGIN;

-- Sedes (lugares de trabajo) y departamentos: listas propias del modulo, editables. El nombre es unico sin
-- distinguir mayusculas (tambien entre las archivadas: una archivada se reutiliza al volver a crearla).
-- import_key guarda la clave que tenia la sede en el sistema de origen para no duplicarla al importar de nuevo.
CREATE TABLE empleados_sites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL CHECK (btrim(name) <> ''),
    position INTEGER NOT NULL DEFAULT 0,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    import_key VARCHAR(200) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX empleados_sites_name_idx ON empleados_sites (lower(name));
CREATE UNIQUE INDEX empleados_sites_import_key_idx ON empleados_sites (import_key) WHERE import_key IS NOT NULL;

CREATE TABLE empleados_departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL CHECK (btrim(name) <> ''),
    position INTEGER NOT NULL DEFAULT 0,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX empleados_departments_name_idx ON empleados_departments (lower(name));

-- Fichas. Solo los datos de trabajo: sin nomina, salarios, documentos de identidad, contacto de emergencia ni nacimiento.
-- user_id enlaza la ficha con la cuenta del empleado (una cuenta, una ficha); no tiene clave foranea porque
-- application_users es del Core: el modulo comprueba la cuenta al escribir.
CREATE TABLE empleados_employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(160) NOT NULL CHECK (btrim(full_name) <> ''),
    job_title VARCHAR(120) NOT NULL DEFAULT '',
    department_id UUID NULL REFERENCES empleados_departments (id) ON DELETE SET NULL,
    site_id UUID NULL REFERENCES empleados_sites (id) ON DELETE SET NULL,
    phone VARCHAR(40) NOT NULL DEFAULT '',
    email VARCHAR(254) NOT NULL DEFAULT '',
    hire_date DATE NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    ended_on DATE NULL,
    availability VARCHAR(500) NOT NULL DEFAULT '',
    notes TEXT NOT NULL DEFAULT '' CHECK (length(notes) <= 5000),
    user_id UUID NULL,
    import_key VARCHAR(200) NULL,
    created_by UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status = 'inactive' OR ended_on IS NULL)
);

CREATE UNIQUE INDEX empleados_employees_user_idx ON empleados_employees (user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX empleados_employees_import_key_idx ON empleados_employees (import_key) WHERE import_key IS NOT NULL;
CREATE INDEX empleados_employees_site_idx ON empleados_employees (site_id);
CREATE INDEX empleados_employees_department_idx ON empleados_employees (department_id);
CREATE INDEX empleados_employees_name_idx ON empleados_employees (lower(full_name), id);

-- Horario habitual: franjas por dia de la semana (ISO: 1 lunes ... 7 domingo). Si la hora de fin es menor que la
-- de inicio la franja pasa de la medianoche. site_id NULL es la sede de la ficha. Maximo 4 franjas por dia (se valida al escribir).
CREATE TABLE empleados_weekly (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES empleados_employees (id) ON DELETE RESTRICT,
    weekday SMALLINT NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL CHECK (end_time <> start_time),
    site_id UUID NULL REFERENCES empleados_sites (id) ON DELETE SET NULL,
    position INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX empleados_weekly_employee_idx ON empleados_weekly (employee_id, weekday);

-- Turnos concretos de una fecha: reemplazan el horario habitual de ese dia. kind 'off' es un dia libre.
CREATE TABLE empleados_shifts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES empleados_employees (id) ON DELETE RESTRICT,
    shift_date DATE NOT NULL,
    kind VARCHAR(8) NOT NULL DEFAULT 'work' CHECK (kind IN ('work', 'off')),
    start_time TIME NULL,
    end_time TIME NULL,
    site_id UUID NULL REFERENCES empleados_sites (id) ON DELETE SET NULL,
    note VARCHAR(500) NOT NULL DEFAULT '',
    created_by UUID NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (kind = 'off' AND start_time IS NULL AND end_time IS NULL)
        OR (kind = 'work' AND start_time IS NOT NULL AND end_time IS NOT NULL AND start_time <> end_time)
    )
);

CREATE INDEX empleados_shifts_date_idx ON empleados_shifts (shift_date);
CREATE INDEX empleados_shifts_employee_date_idx ON empleados_shifts (employee_id, shift_date);

-- Ausencias: las pide el empleado (pending) y quien gestiona turnos las aprueba o rechaza; tambien se pueden
-- registrar ya aprobadas. Una ausencia aprobada anula el horario de esos dias.
CREATE TABLE empleados_absences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id UUID NOT NULL REFERENCES empleados_employees (id) ON DELETE RESTRICT,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('vacation', 'leave', 'sick', 'other')),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled')),
    reason VARCHAR(1000) NOT NULL DEFAULT '',
    decision_note VARCHAR(1000) NOT NULL DEFAULT '',
    requested_by UUID NULL,
    decided_by UUID NULL,
    decided_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date AND end_date - start_date <= 366),
    CHECK (status IN ('pending', 'cancelled') OR decided_at IS NOT NULL)
);

CREATE INDEX empleados_absences_employee_idx ON empleados_absences (employee_id, start_date);
CREATE INDEX empleados_absences_approved_idx ON empleados_absences (start_date, end_date) WHERE status = 'approved';
CREATE INDEX empleados_absences_pending_idx ON empleados_absences (created_at) WHERE status = 'pending';

COMMIT;
