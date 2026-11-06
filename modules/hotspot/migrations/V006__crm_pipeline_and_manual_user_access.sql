BEGIN;

ALTER TABLE hotspot_settings
    ADD COLUMN crm_access_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN crm_stage_id UUID,
    ADD COLUMN crm_schedule_mode VARCHAR(16) NOT NULL DEFAULT 'daily'
        CHECK (crm_schedule_mode IN ('daily', 'interval')),
    ADD COLUMN crm_check_interval_minutes INTEGER NOT NULL DEFAULT 60
        CHECK (crm_check_interval_minutes BETWEEN 1 AND 10080),
    ADD COLUMN crm_check_time TIME NOT NULL DEFAULT '07:00';

-- Employee access codes remain valid until an owner explicitly disables them.
ALTER TABLE hotspot_user_codes
    ADD COLUMN access_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- NULL expiry represents an employee-code session that only an owner can revoke.
ALTER TABLE hotspot_code_sessions
    ALTER COLUMN expires_at DROP NOT NULL,
    DROP CONSTRAINT hotspot_code_sessions_window,
    ADD CONSTRAINT hotspot_code_sessions_window
        CHECK (expires_at IS NULL OR expires_at > started_at);

COMMIT;
