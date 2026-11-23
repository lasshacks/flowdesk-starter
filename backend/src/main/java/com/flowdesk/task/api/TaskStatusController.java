package com.flowdesk.task.api;

import com.flowdesk.common.api.StrictWriteJson;
import com.flowdesk.task.application.TaskCommandService;
import com.flowdesk.task.application.TaskView;
import com.flowdesk.task.domain.InvalidTaskInputException;
import com.flowdesk.task.domain.TaskStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

@RestController
@Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
public class TaskStatusController {
    private final TaskCommandService service;
    public TaskStatusController(TaskCommandService service) { this.service=service; }

    @PatchMapping(value="/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status",produces="application/json")
    public TaskView changeStatus(@PathVariable String workspaceId,@PathVariable String projectId,
                                 @PathVariable String taskId,HttpServletRequest request) throws IOException {
        long workspace=TaskController.id(workspaceId),project=TaskController.id(projectId),task=TaskController.id(taskId);
        var body=StrictWriteJson.read(request,32768);
        if(body.size()!=2 || !body.path("status").isTextual() || !body.path("version").isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        TaskStatus target;
        try { target=TaskStatus.valueOf(body.get("status").textValue()); }
        catch(IllegalArgumentException invalid) { throw new InvalidTaskInputException(); }
        return service.changeStatus(workspace,project,task,target,TaskController.version(body.get("version").textValue()));
    }
}
