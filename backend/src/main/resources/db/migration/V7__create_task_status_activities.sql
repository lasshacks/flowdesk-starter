CREATE TABLE flowdesk.task_status_activities (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id bigint NOT NULL REFERENCES flowdesk.tasks(id) ON DELETE RESTRICT,
    actor_user_id bigint NOT NULL REFERENCES flowdesk.users(id) ON DELETE RESTRICT,
    from_status varchar(16) NOT NULL CHECK (from_status IN ('TODO','IN_PROGRESS','DONE')),
    to_status varchar(16) NOT NULL CHECK (to_status IN ('TODO','IN_PROGRESS','DONE')),
    task_version bigint NOT NULL CHECK (task_version > 0),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT task_status_activities_transition CHECK (from_status <> to_status),
    CONSTRAINT task_status_activities_task_version_key UNIQUE (task_id, task_version)
);
