package com.flowdesk.task.application;
import com.flowdesk.task.domain.Task;
public record TaskView(String id,String workspaceId,String projectId,String title,String description,String status,String priority,String version,String assigneeId,String dueDate,boolean archived,java.time.Instant archivedAt) {
    public static TaskView from(long workspaceId,Task t){return new TaskView(t.getId().toString(),Long.toString(workspaceId),t.getProjectId().toString(),t.getTitle(),t.getDescription(),t.getStatus().name(),t.getPriority().name(),t.getVersion().toString(),t.getAssigneeId()==null?null:t.getAssigneeId().toString(),t.getDueDate()==null?null:t.getDueDate().toString(),t.isArchived(),t.getArchivedAt());}
}
