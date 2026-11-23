package com.flowdesk.project.api;

import com.flowdesk.common.api.StrictWriteJson;
import com.flowdesk.project.application.ProjectCommandService;
import com.flowdesk.project.application.ProjectView;
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
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="flowdesk.dev.project-archive-enabled",havingValue="true")
public class ProjectArchiveController {
    private final ProjectCommandService service;
    public ProjectArchiveController(ProjectCommandService service) { this.service = service; }

    @PatchMapping(value="/api/workspaces/{workspaceId}/projects/{projectId}/archive",produces="application/json")
    public ProjectView change(@PathVariable String workspaceId, @PathVariable String projectId,
                              HttpServletRequest request) throws IOException {
        long parent=ProjectController.id(workspaceId), id=ProjectController.id(projectId);
        var body=StrictWriteJson.read(request,4096);
        if(body.size()!=2 || !body.has("archived") || !body.get("archived").isBoolean() || !body.path("version").isTextual())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return service.setArchived(parent,id,body.get("archived").booleanValue(),ProjectController.version(body.get("version").textValue()));
    }
}
