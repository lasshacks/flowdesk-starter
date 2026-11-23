package com.flowdesk.workspace.api;

import com.flowdesk.workspace.application.WorkspaceCommandService;
import com.flowdesk.workspace.application.WorkspaceView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;

@RestController
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
public class WorkspaceCreateController {
    private final WorkspaceCommandService service;

    public WorkspaceCreateController(WorkspaceCommandService service) { this.service = service; }

    @PostMapping(value = "/api/workspaces", produces = "application/json")
    public ResponseEntity<WorkspaceView> create(HttpServletRequest request) throws IOException {
        var body = WorkspaceWriteJson.read(request);
        if (body.size() != 1 || !body.path("name").isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var input = new CreateWorkspaceRequest(body.get("name").textValue());
        var result = service.create(input.name());
        return ResponseEntity.created(URI.create("/api/workspaces/" + result.id())).body(result);
    }

    private record CreateWorkspaceRequest(String name) { }
}
