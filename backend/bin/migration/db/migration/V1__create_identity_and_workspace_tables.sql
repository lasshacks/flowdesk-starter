CREATE TABLE flowdesk.users (
    id bigint GENERATED ALWAYS AS IDENTITY,
    email varchar(254) NOT NULL,
    display_name varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (
        email = lower(btrim(email)) AND char_length(email) > 0
    ),
    CONSTRAINT ck_users_display_name_not_blank CHECK (char_length(btrim(display_name)) > 0)
);

CREATE TABLE flowdesk.workspaces (
    id bigint GENERATED ALWAYS AS IDENTITY,
    name varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_workspaces PRIMARY KEY (id),
    CONSTRAINT ck_workspaces_name_not_blank CHECK (char_length(btrim(name)) > 0)
);

CREATE TABLE flowdesk.workspace_memberships (
    id bigint GENERATED ALWAYS AS IDENTITY,
    workspace_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_workspace_memberships PRIMARY KEY (id),
    CONSTRAINT fk_workspace_memberships_workspace FOREIGN KEY (workspace_id)
        REFERENCES flowdesk.workspaces (id) ON DELETE RESTRICT,
    CONSTRAINT fk_workspace_memberships_user FOREIGN KEY (user_id)
        REFERENCES flowdesk.users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_workspace_memberships_workspace_user UNIQUE (workspace_id, user_id),
    CONSTRAINT ck_workspace_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER'))
);

-- At most one OWNER; the Service transaction must also prevent zero owners.
CREATE UNIQUE INDEX uq_workspace_memberships_owner
    ON flowdesk.workspace_memberships (workspace_id) WHERE role = 'OWNER';

CREATE INDEX idx_workspace_memberships_user
    ON flowdesk.workspace_memberships (user_id);
