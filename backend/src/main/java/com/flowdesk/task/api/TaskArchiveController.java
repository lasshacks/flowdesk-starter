package com.flowdesk.task.api;

import com.flowdesk.common.api.StrictWriteJson;
import com.flowdesk.task.application.TaskCommandService;
import com.flowdesk.task.application.TaskView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;

@RestController
@Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
@ConditionalOnProperty(name="flowdesk.dev.task-archive-enabled",havingValue="true")
public class TaskArchiveController {
    private final TaskCommandService service;
    public TaskArchiveController(TaskCommandService service) { this.service=service; }

    @PatchMapping(value="/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/archive",produces="application/json")
    public TaskView change(@PathVariable String workspaceId,@PathVariable String projectId,@PathVariable String taskId,
                           HttpServletRequest request) throws IOException {
        long workspace=TaskController.id(workspaceId),project=TaskController.id(projectId),task=TaskController.id(taskId);
        var body=StrictWriteJson.read(request,4096);
        if(body.size()!=2 || !body.has("archived") || !body.get("archived").isBoolean() || !body.path("version").isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return service.setArchived(workspace,project,task,body.get("archived").booleanValue(),TaskController.version(body.get("version").textValue()));
    }
}
