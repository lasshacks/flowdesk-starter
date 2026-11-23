REVOKE SELECT, INSERT ON flowdesk.tasks FROM flowdesk_app;
GRANT SELECT (id,project_id,title,description,status,priority,created_at,updated_at,version,assignee_id,due_date,archived_at)
    ON flowdesk.tasks TO flowdesk_app;
GRANT INSERT (project_id,title,description,status,priority,version,assignee_id,due_date)
    ON flowdesk.tasks TO flowdesk_app;
GRANT UPDATE (assignee_id,due_date) ON flowdesk.tasks TO flowdesk_app;
