package com.flowdesk.workspace.application;

import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.workspace.domain.Workspace;
import com.flowdesk.workspace.repository.WorkspaceCreationRepository;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import com.flowdesk.workspace.repository.WorkspaceRenameRepository;
import com.flowdesk.workspace.domain.WorkspaceRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
public class WorkspaceCommandService {
    private final DevActorProvider actors;
    private final WorkspaceCreationRepository creation;

    private final WorkspaceMembershipRepository memberships;
    private final WorkspaceRenameRepository renaming;

    public WorkspaceCommandService(DevActorProvider actors, WorkspaceCreationRepository creation,
            WorkspaceMembershipRepository memberships, WorkspaceRenameRepository renaming) {
        this.actors = actors;
        this.creation = creation;
        this.memberships = memberships;
        this.renaming = renaming;
    }

    @Transactional(readOnly = false, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public WorkspaceView rename(long id, String name, long version) {
        var actor = actors.current();
        var membership = memberships.findByWorkspace_IdAndUser_Id(id, actor.userId())
                .orElseThrow(WorkspaceNotFoundException::new);
        if (membership.getRole() != WorkspaceRole.OWNER && membership.getRole() != WorkspaceRole.ADMIN) {
            throw new WorkspaceForbiddenException();
        }
        var workspace = membership.getWorkspace();
        if (workspace.getVersion() != version) throw new WorkspaceVersionConflictException();
        renaming.rename(workspace, name);
        return WorkspaceView.from(membership);
    }

    @Transactional(readOnly = false, isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public WorkspaceView create(String name) {
        var workspace = Workspace.create(name);
        var actor = actors.current();
        return WorkspaceView.from(creation.persist(workspace, actor.userId()));
    }
}
