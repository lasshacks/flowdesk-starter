CREATE TABLE flowdesk.tasks (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES flowdesk.projects(id) ON DELETE RESTRICT,
    title varchar(200) NOT NULL CHECK (char_length(btrim(title)) > 0),
    description varchar(2000),
    status varchar(16) NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO','IN_PROGRESS','DONE')),
    priority varchar(6) NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX tasks_project_id_id_idx ON flowdesk.tasks(project_id,id);
