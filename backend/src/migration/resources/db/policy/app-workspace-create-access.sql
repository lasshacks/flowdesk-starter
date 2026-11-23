-- Explicit prerequisite: approved READ_ONLY policy. No data/sequence/history/default grants.
GRANT INSERT ON TABLE flowdesk.workspaces, flowdesk.workspace_memberships TO flowdesk_app;
