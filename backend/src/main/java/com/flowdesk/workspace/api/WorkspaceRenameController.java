package com.flowdesk.workspace.api;

import com.flowdesk.workspace.application.WorkspaceCommandService;
import com.flowdesk.workspace.application.WorkspaceView;
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
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
public class WorkspaceRenameController {
    private final WorkspaceCommandService service;
    public WorkspaceRenameController(WorkspaceCommandService service) { this.service = service; }

    @PatchMapping(value = "/api/workspaces/{workspaceId}", produces = "application/json")
    public WorkspaceView rename(@PathVariable String workspaceId, HttpServletRequest request) throws IOException {
        long id = WorkspaceController.number(workspaceId, 1, Long.MAX_VALUE, "");
        var body = WorkspaceWriteJson.read(request);
        if (body.size() != 2 || !body.path("name").isTextual() || !body.path("version").isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String version = body.get("version").textValue();
        if (!version.matches("0|[1-9][0-9]{0,18}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return service.rename(id, body.get("name").textValue(),
                WorkspaceController.number(version, 0, Long.MAX_VALUE, ""));
    }
}
