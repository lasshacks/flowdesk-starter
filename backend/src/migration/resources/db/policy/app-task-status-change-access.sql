GRANT UPDATE(status) ON flowdesk.tasks TO flowdesk_app;
GRANT INSERT(task_id,actor_user_id,from_status,to_status,task_version,occurred_at)
    ON flowdesk.task_status_activities TO flowdesk_app;
