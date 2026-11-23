package com.flowdesk.task.api;

import com.flowdesk.task.application.TaskStatusHistory;
import com.flowdesk.task.application.TaskStatusHistoryQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

@RestController
@Profile("dev-actor")
public class TaskStatusHistoryController {
    private final TaskStatusHistoryQueryService service;
    public TaskStatusHistoryController(TaskStatusHistoryQueryService service) { this.service=service; }
    @GetMapping(value="/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status-activities",produces="application/json")
    public TaskStatusHistory list(@PathVariable String workspaceId,@PathVariable String projectId,@PathVariable String taskId,
                                 @RequestParam MultiValueMap<String,String> query) {
        TaskController.keys(query,Set.of("beforeVersion","size"));
        Long before=query.containsKey("beforeVersion")?TaskController.id(query.getFirst("beforeVersion")):null;
        long size=query.containsKey("size")?TaskController.id(query.getFirst("size")):20;
        if(size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return service.list(TaskController.id(workspaceId),TaskController.id(projectId),TaskController.id(taskId),before,(int)size);
    }
}
