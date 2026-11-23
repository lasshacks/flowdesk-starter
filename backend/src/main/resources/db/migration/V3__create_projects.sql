CREATE TABLE flowdesk.projects (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workspace_id bigint NOT NULL REFERENCES flowdesk.workspaces(id) ON DELETE RESTRICT,
    name varchar(100) NOT NULL CHECK (char_length(btrim(name)) > 0),
    description varchar(2000),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX projects_workspace_id_id_idx ON flowdesk.projects(workspace_id, id);
