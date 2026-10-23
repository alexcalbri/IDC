BEGIN;

CREATE TABLE application_permissions (
    permission_id VARCHAR(128) PRIMARY KEY CHECK (permission_id ~ '^[a-z][a-z0-9_.-]{0,127}$'),
    module_id VARCHAR(63) NOT NULL CHECK (module_id ~ '^[a-z][a-z0-9_]{0,62}$'),
    title TEXT NOT NULL CHECK (length(trim(title)) > 0),
    description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE application_user_permissions (
    user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    permission_id VARCHAR(128) NOT NULL REFERENCES application_permissions(permission_id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, permission_id)
);

CREATE INDEX application_permissions_module_idx
    ON application_permissions (module_id, permission_id);

CREATE INDEX application_user_permissions_permission_idx
    ON application_user_permissions (permission_id);

INSERT INTO application_permissions (permission_id, module_id, title, description)
VALUES
    ('company.manage', 'empresa', 'Administrar empresa', 'Permite actualizar el perfil de la empresa propia.'),
    ('users.manage', 'empresa', 'Administrar usuarios', 'Permite crear usuarios y asignar permisos dentro de la empresa propia.'),
    ('clientes.view', 'clientes', 'Ver clientes', 'Permite consultar la lista y ficha de clientes.'),
    ('clientes.create', 'clientes', 'Crear clientes', 'Permite crear clientes.'),
    ('clientes.edit', 'clientes', 'Editar clientes', 'Permite modificar clientes existentes.'),
    ('clientes.delete', 'clientes', 'Eliminar clientes', 'Permite eliminar o desactivar clientes.'),
    ('clientes.fields.manage', 'clientes', 'Administrar campos de clientes', 'Permite crear, editar, desactivar o eliminar campos dinamicos de clientes.')
ON CONFLICT (permission_id)
DO UPDATE SET
    module_id = EXCLUDED.module_id,
    title = EXCLUDED.title,
    description = EXCLUDED.description;

INSERT INTO application_user_permissions (user_id, permission_id)
SELECT user_id, permission_id
FROM business_owner
CROSS JOIN (
    VALUES
        ('company.manage'),
        ('users.manage'),
        ('clientes.view'),
        ('clientes.create'),
        ('clientes.edit'),
        ('clientes.delete'),
        ('clientes.fields.manage')
) AS permissions(permission_id)
ON CONFLICT (user_id, permission_id) DO NOTHING;

COMMIT;
