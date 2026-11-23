package com.flowdesk.task.api;

import com.flowdesk.common.api.StrictWriteJson;
import com.flowdesk.task.application.TaskCommandService;
import com.flowdesk.task.application.TaskView;
import com.flowdesk.task.domain.Task;
import com.flowdesk.task.domain.TaskPriority;
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
public class TaskUpdateController {
    private final TaskCommandService service;
    public TaskUpdateController(TaskCommandService service) { this.service=service; }
    @PatchMapping(value="/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}",produces="application/json")
    public TaskView update(@PathVariable String workspaceId,@PathVariable String projectId,@PathVariable String taskId,
                           HttpServletRequest request) throws IOException {
        long workspace=TaskController.id(workspaceId),project=TaskController.id(projectId),task=TaskController.id(taskId);
        var body=StrictWriteJson.read(request,32768);
        if(body.size()!=6 || !body.path("title").isTextual() || !body.path("priority").isTextual() || !body.path("version").isTextual()
                || !body.has("description") || !(body.get("description").isNull() || body.get("description").isTextual())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if(!body.has("assigneeId") || !body.has("dueDate"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var input=new UpdateTaskRequest(body.get("title").textValue(),body.hasNonNull("description")?body.get("description").textValue():null,
                Task.priority(body.get("priority").textValue()),TaskController.version(body.get("version").textValue()),
                TaskController.nullableId(body.get("assigneeId")),TaskController.nullableDate(body.get("dueDate")));
        return service.update(workspace,project,task,input.title(),input.description(),input.priority(),input.version(),input.assigneeId(),input.dueDate());
    }
    private record UpdateTaskRequest(String title,String description,TaskPriority priority,long version,Long assigneeId,java.time.LocalDate dueDate) {
        private UpdateTaskRequest { title=Task.normalizeTitle(title);description=Task.normalizeDescription(description); }
    }
}
