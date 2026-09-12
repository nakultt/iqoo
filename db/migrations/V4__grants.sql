-- =============================================================================
-- VeriTransit — V4: least-privilege grants for the application roles
--
-- Roles themselves (and their passwords) are created by db/scripts/setup.sh and
-- kept out of version control; this migration only assigns privileges, and does
-- nothing if the roles are absent — so the schema still applies on a machine
-- that has not run setup.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'veritransit_app') THEN
        RAISE NOTICE 'role veritransit_app absent — skipping grants';
        RETURN;
    END IF;

    -- Read/write role: the Ktor backend and the Telegram bot.
    EXECUTE 'GRANT USAGE ON SCHEMA public TO veritransit_app';
    EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO veritransit_app';
    EXECUTE 'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO veritransit_app';
    EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO veritransit_app';

    -- §3 check 9: history is append-only even for the application role. The
    -- trigger already refuses rewrites; withholding the privilege means the
    -- attempt never reaches it.
    EXECUTE 'REVOKE UPDATE, DELETE ON audit_log FROM veritransit_app';

    -- Future tables inherit the same shape.
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public '
            'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO veritransit_app';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public '
            'GRANT USAGE, SELECT ON SEQUENCES TO veritransit_app';
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'veritransit_ro') THEN
        RAISE NOTICE 'role veritransit_ro absent — skipping grants';
        RETURN;
    END IF;

    -- Read-only role: dashboards, analytics, a laptop psql from another device.
    EXECUTE 'GRANT USAGE ON SCHEMA public TO veritransit_ro';
    EXECUTE 'GRANT SELECT ON ALL TABLES IN SCHEMA public TO veritransit_ro';
    EXECUTE 'GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO veritransit_ro';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO veritransit_ro';
END $$;
