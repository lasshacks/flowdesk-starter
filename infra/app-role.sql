-- The caller sets flowdesk.app_password for this session; no password is stored here.
BEGIN;

DO $provision$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'flowdesk_app') THEN
        CREATE ROLE flowdesk_app;
    END IF;

    EXECUTE format(
        'ALTER ROLE flowdesk_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE '
        'NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',
        current_setting('flowdesk.app_password')
    );

    -- PUBLIC otherwise grants CONNECT and TEMPORARY to every login role.
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM flowdesk_app', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO flowdesk_app', current_database());
END
$provision$;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM flowdesk_app;

COMMIT;
