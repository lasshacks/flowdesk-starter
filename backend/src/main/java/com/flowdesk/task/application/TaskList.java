package com.flowdesk.task.application;
import com.flowdesk.task.domain.Task;
import java.util.List;
public record TaskList(List<Item> items,int page,int size,boolean hasNext) {
    public record Item(String id,String workspaceId,String projectId,String title,String status,String priority,String assigneeId,String dueDate,boolean archived) {
        public static Item from(long workspaceId,Task t){return new Item(t.getId().toString(),Long.toString(workspaceId),t.getProjectId().toString(),t.getTitle(),t.getStatus().name(),t.getPriority().name(),t.getAssigneeId()==null?null:t.getAssigneeId().toString(),t.getDueDate()==null?null:t.getDueDate().toString(),t.isArchived());}
    }
}
