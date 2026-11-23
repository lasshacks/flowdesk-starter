-- Explicit objects only. No passwords, DML, sequences, history or default privileges.
GRANT USAGE ON SCHEMA flowdesk TO flowdesk_app;
GRANT SELECT ON TABLE flowdesk.users, flowdesk.workspaces, flowdesk.workspace_memberships TO flowdesk_app;
