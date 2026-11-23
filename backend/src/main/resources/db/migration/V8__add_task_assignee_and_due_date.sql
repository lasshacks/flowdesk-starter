ALTER TABLE flowdesk.tasks
    ADD COLUMN assignee_id bigint REFERENCES flowdesk.users(id) ON DELETE RESTRICT,
    ADD COLUMN due_date date;
