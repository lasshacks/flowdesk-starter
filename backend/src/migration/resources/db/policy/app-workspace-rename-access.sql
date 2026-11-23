-- Prerequisite: exact WORKSPACE_CREATE; no table-wide UPDATE or other grants.
GRANT UPDATE (name, updated_at, version) ON TABLE flowdesk.workspaces TO flowdesk_app;
