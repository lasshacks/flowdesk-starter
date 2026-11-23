-- Membership removal needs relation-level DELETE (PostgreSQL has no column-level DELETE).
GRANT DELETE ON TABLE flowdesk.workspace_memberships TO flowdesk_app;
GRANT UPDATE (role, updated_at) ON TABLE flowdesk.workspace_memberships TO flowdesk_app;

-- Invitation reads are column-scoped; raw invite codes are never stored in the table.
GRANT SELECT (id, workspace_id, target_user_id, invited_by_user_id, role, token_digest,
              status, created_at, expires_at, accepted_at)
    ON TABLE flowdesk.workspace_invitations TO flowdesk_app;
GRANT INSERT (workspace_id, target_user_id, invited_by_user_id, role, token_digest,
              status, expires_at, accepted_at)
    ON TABLE flowdesk.workspace_invitations TO flowdesk_app;
GRANT UPDATE (status, accepted_at) ON TABLE flowdesk.workspace_invitations TO flowdesk_app;
