package com.flowdesk.workspace.application;

import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.workspace.domain.WorkspaceRole;
import com.flowdesk.workspace.repository.WorkspaceInvitationReadRepository;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
@Transactional(readOnly = true)
public class WorkspaceInvitationQueryService {
    private final DevActorProvider actors;
    private final WorkspaceMembershipRepository memberships;
    private final WorkspaceInvitationReadRepository invitations;

    public WorkspaceInvitationQueryService(DevActorProvider actors, WorkspaceMembershipRepository memberships,
            WorkspaceInvitationReadRepository invitations) {
        this.actors = actors;
        this.memberships = memberships;
        this.invitations = invitations;
    }

    public WorkspaceInvitationList pending(long workspaceId, int page, int size) {
        var membership = memberships.findByWorkspace_IdAndUser_Id(workspaceId, actors.current().userId())
                .orElseThrow(WorkspaceNotFoundException::new);
        var role = membership.getRole();
        if (role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN) throw new WorkspaceForbiddenException();

        Instant now = Instant.now();
        var slice = invitations.findPendingByWorkspaceId(workspaceId, now, PageRequest.of(page, size));
        return new WorkspaceInvitationList(slice.getContent().stream().map(WorkspaceInvitationList.Item::from).toList(),
                page, size, slice.hasNext());
    }
}
