package com.flowdesk.task.api;
import com.flowdesk.task.application.*;
import com.flowdesk.task.domain.*;
import com.flowdesk.common.api.StrictWriteJson;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.io.IOException;
@RestController @Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
public class TaskCreateController {
    private final TaskCommandService service;
    public TaskCreateController(TaskCommandService service){this.service=service;}
    @PostMapping(value="/api/workspaces/{workspaceId}/projects/{projectId}/tasks",produces="application/json")
    public ResponseEntity<TaskView> create(@PathVariable String workspaceId,@PathVariable String projectId,HttpServletRequest request)throws IOException{
        long parent=TaskController.id(workspaceId),project=TaskController.id(projectId);
        var body=StrictWriteJson.read(request,32768);var fields=body.fieldNames();
        while(fields.hasNext())if(!java.util.Set.of("title","description","priority","assigneeId","dueDate").contains(fields.next()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if(!body.path("title").isTextual() || (body.hasNonNull("description")&&!body.path("description").isTextual())
                || (body.has("priority")&&(!body.hasNonNull("priority")||!body.path("priority").isTextual())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var input=new CreateTaskRequest(body.get("title").textValue(),body.hasNonNull("description")?body.get("description").textValue():null,
                Task.priority(body.has("priority")?body.get("priority").textValue():null),TaskController.nullableId(body.get("assigneeId")),TaskController.nullableDate(body.get("dueDate")));
        var result=service.create(parent,project,input.title(),input.description(),input.priority(),input.assigneeId(),input.dueDate());
        return ResponseEntity.created(URI.create("/api/workspaces/"+result.workspaceId()+"/projects/"+result.projectId()+"/tasks/"+result.id())).body(result);
    }
    private record CreateTaskRequest(String title,String description,TaskPriority priority,Long assigneeId,java.time.LocalDate dueDate){
        private CreateTaskRequest{title=Task.normalizeTitle(title);description=Task.normalizeDescription(description);}
    }
}
