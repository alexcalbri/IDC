BEGIN;

-- Colors of the portal set in Hotspot > Diseno. NULL uses the company's color (Empresa), which is
-- IDC's until changed. The old primary_color/text_color columns stay unused.
ALTER TABLE hotspot_settings
    ADD COLUMN portal_primary_color CHAR(7) CHECK (portal_primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    ADD COLUMN portal_secondary_color CHAR(7) CHECK (portal_secondary_color ~ '^#[0-9A-Fa-f]{6}$'),
    ADD COLUMN portal_text_color CHAR(7) CHECK (portal_text_color ~ '^#[0-9A-Fa-f]{6}$');

COMMIT;
