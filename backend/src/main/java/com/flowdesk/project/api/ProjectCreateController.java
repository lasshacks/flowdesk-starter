package com.flowdesk.project.api;
import com.flowdesk.project.application.*;
import com.flowdesk.project.domain.Project;
import com.flowdesk.common.api.StrictWriteJson;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
@RestController @Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
public class ProjectCreateController {
    private final ProjectCommandService service;
    public ProjectCreateController(ProjectCommandService service) { this.service = service; }
    @PostMapping(value="/api/workspaces/{workspaceId}/projects",produces="application/json")
    public ResponseEntity<ProjectView> create(@PathVariable String workspaceId,
                                              HttpServletRequest request) throws IOException {
        long parent = ProjectController.id(workspaceId);
        var body = StrictWriteJson.read(request, 32768);
        var fields = body.fieldNames();
        while (fields.hasNext()) {
            if (!java.util.Set.of("name", "description").contains(fields.next())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
        if (!body.path("name").isTextual()
                || (body.hasNonNull("description") && !body.path("description").isTextual())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        // Validate before actor lookup, without binding client fields to a managed Entity.
        var input = new CreateProjectRequest(body.get("name").textValue(),
                body.hasNonNull("description") ? body.get("description").textValue() : null);
        var result = service.create(parent, input.name(), input.description());
        return ResponseEntity.created(URI.create("/api/workspaces/" + result.workspaceId()
                + "/projects/" + result.id())).body(result);
    }
    private record CreateProjectRequest(String name, String description) {
        private CreateProjectRequest {
            name = Project.normalizeName(name);
            description = Project.normalizeDescription(description);
        }
    }
}
