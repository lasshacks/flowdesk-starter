package com.flowdesk.project.api;

import com.flowdesk.common.api.StrictWriteJson;
import com.flowdesk.project.application.ProjectCommandService;
import com.flowdesk.project.application.ProjectView;
import com.flowdesk.project.domain.Project;
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
public class ProjectUpdateController {
    private final ProjectCommandService service;
    public ProjectUpdateController(ProjectCommandService service) { this.service = service; }

    @PatchMapping(value="/api/workspaces/{workspaceId}/projects/{projectId}",produces="application/json")
    public ProjectView update(@PathVariable String workspaceId, @PathVariable String projectId,
                              HttpServletRequest request) throws IOException {
        long parent = ProjectController.id(workspaceId);
        long id = ProjectController.id(projectId);
        var body = StrictWriteJson.read(request, 32768);
        if (body.size() != 3 || !body.path("name").isTextual() || !body.path("version").isTextual()
                || !body.has("description") || !(body.get("description").isNull() || body.get("description").isTextual())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var input = new UpdateProjectRequest(body.get("name").textValue(),
                body.hasNonNull("description") ? body.get("description").textValue() : null,
                ProjectController.version(body.get("version").textValue()));
        return service.update(parent, id, input.name(), input.description(), input.version());
    }

    private record UpdateProjectRequest(String name, String description, long version) {
        private UpdateProjectRequest {
            name = Project.normalizeName(name);
            description = Project.normalizeDescription(description);
        }
    }
}
