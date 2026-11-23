-- Replace broad SELECT on current identity/workspace tables with exact current mapped columns.
-- This preserves existing reads while preventing future columns from inheriting access.
REVOKE SELECT ON TABLE flowdesk.users, flowdesk.workspaces, flowdesk.workspace_memberships FROM flowdesk_app;
GRANT SELECT (id, email, display_name, created_at, updated_at) ON flowdesk.users TO flowdesk_app;
GRANT SELECT (id, name, created_at, updated_at, version) ON flowdesk.workspaces TO flowdesk_app;
GRANT SELECT (id, user_id, workspace_id, role, created_at, updated_at) ON flowdesk.workspace_memberships TO flowdesk_app;
