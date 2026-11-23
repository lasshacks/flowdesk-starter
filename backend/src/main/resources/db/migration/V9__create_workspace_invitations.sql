CREATE TABLE flowdesk.workspace_invitations (
    id bigint GENERATED ALWAYS AS IDENTITY,
    workspace_id bigint NOT NULL,
    target_user_id bigint NOT NULL,
    invited_by_user_id bigint NOT NULL,
    role varchar(16) NOT NULL,
    token_digest varchar(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    CONSTRAINT pk_workspace_invitations PRIMARY KEY (id),
    CONSTRAINT fk_workspace_invitations_workspace FOREIGN KEY (workspace_id)
        REFERENCES flowdesk.workspaces(id) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_invitations_target FOREIGN KEY (target_user_id)
        REFERENCES flowdesk.users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_invitations_inviter FOREIGN KEY (invited_by_user_id)
        REFERENCES flowdesk.users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_workspace_invitations_role CHECK (role IN ('ADMIN','MEMBER','VIEWER')),
    CONSTRAINT ck_workspace_invitations_status CHECK (status IN ('PENDING','ACCEPTED','REVOKED')),
    CONSTRAINT ck_workspace_invitations_digest CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workspace_invitations_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_workspace_invitations_acceptance CHECK
        ((status = 'ACCEPTED' AND accepted_at IS NOT NULL) OR (status <> 'ACCEPTED' AND accepted_at IS NULL)),
    CONSTRAINT uq_workspace_invitations_token_digest UNIQUE (token_digest)
);

CREATE UNIQUE INDEX uq_workspace_invitations_pending_target
    ON flowdesk.workspace_invitations(workspace_id,target_user_id) WHERE status='PENDING';

CREATE INDEX idx_workspace_invitations_expiry
    ON flowdesk.workspace_invitations(expires_at) WHERE status='PENDING';
