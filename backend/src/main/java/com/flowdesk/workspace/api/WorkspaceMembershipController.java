package com.flowdesk.workspace.api;

import com.flowdesk.workspace.application.WorkspaceInvitationView;
import com.flowdesk.workspace.application.WorkspaceInvitationList;
import com.flowdesk.workspace.application.WorkspaceInvitationQueryService;
import com.flowdesk.workspace.application.WorkspaceMemberView;
import com.flowdesk.workspace.application.WorkspaceMembershipManagementService;
import com.flowdesk.workspace.application.WorkspaceOwnershipView;
import com.flowdesk.workspace.domain.WorkspaceRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

@RestController
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
@RequestMapping(produces = "application/json")
public class WorkspaceMembershipController {
    private final WorkspaceMembershipManagementService service;
    private final WorkspaceInvitationQueryService invitationQueries;

    public WorkspaceMembershipController(WorkspaceMembershipManagementService service,
            WorkspaceInvitationQueryService invitationQueries) {
        this.service = service;
        this.invitationQueries = invitationQueries;
    }

    @GetMapping("/api/workspaces/{workspaceId}/invitations")
    public WorkspaceInvitationList invitations(@PathVariable String workspaceId,
            @RequestParam MultiValueMap<String, String> query) {
        validateKeys(query, Set.of("page", "size"));
        long id = WorkspaceController.number(workspaceId, 1, Long.MAX_VALUE, "");
        int page = (int) WorkspaceController.number(query.getFirst("page"), 0, 10000, "0");
        int size = (int) WorkspaceController.number(query.getFirst("size"), 1, 100, "20");
        return invitationQueries.pending(id, page, size);
    }

    @PostMapping("/api/workspaces/{workspaceId}/invitations")
    public ResponseEntity<WorkspaceInvitationView> issue(@PathVariable String workspaceId, HttpServletRequest request) throws IOException {
        long id = WorkspaceController.number(workspaceId, 1, Long.MAX_VALUE, "");
        var body = WorkspaceWriteJson.read(request);
        if (body.size() != 2 || !body.path("email").isTextual() || !body.path("role").isTextual()) badRequest();
        var result = service.issue(id, body.get("email").textValue(), role(body.get("role").textValue(), false));
        return ResponseEntity.created(URI.create("/api/workspaces/" + id + "/invitations/" + result.id())).body(result);
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<Void> cancel(@PathVariable String workspaceId, @PathVariable String invitationId, HttpServletRequest request) throws IOException {
        WorkspaceWriteJson.requireEmpty(request);
        service.cancel(WorkspaceController.number(workspaceId, 1, Long.MAX_VALUE, ""),
                WorkspaceController.number(invitationId, 1, Long.MAX_VALUE, ""));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/workspace-invitations/accept")
    public ResponseEntity<WorkspaceMemberView> accept(HttpServletRequest request) throws IOException {
        var body = WorkspaceWriteJson.read(request);
        if (body.size() != 1 || !body.path("code").isTextual()) badRequest();
        var member = service.accept(body.get("code").textValue());
        return ResponseEntity.created(URI.create("/api/workspaces/" + member.workspaceId() + "/members/" + member.id())).body(member);
    }

    @PatchMapping("/api/workspaces/{workspaceId}/members/{userId}")
    public WorkspaceMemberView changeRole(@PathVariable String workspaceId, @PathVariable String userId, HttpServletRequest request) throws IOException {
        long id = WorkspaceController.number(workspaceId, 1, Long.MAX_VALUE, "");
        long targetId = WorkspaceController.number(userId, 1, Long.MAX_VALUE, "");
        var body = WorkspaceWriteJson.read(request);
        if (body.size() != 1 || !body.path("role").isTextual()) badRequest();
        return service.changeRole(id, targetId, role(body.get("role").textValue(), false));
    }

    @DeleteMapping("/api/workspaces/{workspaceId}/members/{userId}")
    public ResponseEntity<Void> remove(@PathVariable String workspaceId, @PathVariable String userId, HttpServletRequest request) throws IOException {
        WorkspaceWriteJson.requireEmpty(request);
        service.remove(WorkspaceController.number(workspaceId, 1, Long.MAX_VALUE, ""),
                WorkspaceController.number(userId, 1, Long.MAX_VALUE, ""));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/workspaces/{workspaceId}/ownership-transfer")
    public WorkspaceOwnershipView transfer(@PathVariable String workspaceId, HttpServletRequest request) throws IOException {
        long id = WorkspaceController.number(workspaceId, 1, Long.MAX_VALUE, "");
        var body = WorkspaceWriteJson.read(request);
        if (body.size() != 1 || !body.path("targetUserId").isTextual()) badRequest();
        long targetId = WorkspaceController.number(body.get("targetUserId").textValue(), 1, Long.MAX_VALUE, "");
        return service.transferOwnership(id, targetId);
    }

    static WorkspaceRole role(String input, boolean allowOwner) {
        try {
            var role = WorkspaceRole.valueOf(input);
            if (!allowOwner && role == WorkspaceRole.OWNER) throw new IllegalArgumentException();
            return role;
        } catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    }

    private static void badRequest() { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }

    private static void validateKeys(MultiValueMap<String, String> query, Set<String> allowed) {
        if (query.entrySet().stream().anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().size() != 1)) {
            badRequest();
        }
    }
}
