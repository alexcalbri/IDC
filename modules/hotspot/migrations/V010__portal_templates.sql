BEGIN;

-- Custom HTML of the portal entry views, written by the company. Without a row the view uses the
-- built-in design. The server validates each template before saving it (see HotspotTemplates):
-- the form, field names and placeholders the portal needs must be present and no scripts allowed.
CREATE TABLE hotspot_portal_templates (
    view VARCHAR(32) PRIMARY KEY
        CHECK (view IN ('formulario', 'codigo_cliente', 'codigo_usuario')),
    html TEXT NOT NULL CHECK (length(html) BETWEEN 1 AND 100000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMIT;
