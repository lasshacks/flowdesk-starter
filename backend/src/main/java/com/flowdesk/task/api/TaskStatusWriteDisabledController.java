package com.flowdesk.task.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Preserve the explicit write-off contract for a route with no read counterpart. */
@RestController
@Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="false",matchIfMissing=true)
public class TaskStatusWriteDisabledController {
    @PatchMapping("/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status")
    public void disabled() { throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED); }
}
