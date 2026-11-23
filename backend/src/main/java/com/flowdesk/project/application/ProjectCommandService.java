package com.flowdesk.project.application;
import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import com.flowdesk.workspace.domain.WorkspaceRole;
import com.flowdesk.project.domain.Project;
import com.flowdesk.project.repository.ProjectCreationRepository;
import com.flowdesk.project.repository.ProjectRepository;
import com.flowdesk.project.repository.ProjectUpdateRepository;
import com.flowdesk.workspace.repository.WorkspaceWriteLockRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service @Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
public class ProjectCommandService {
    private final DevActorProvider actors;
    private final WorkspaceMembershipRepository memberships;
    private final ProjectCreationRepository creation;
    private final ProjectRepository projects;
    private final ProjectUpdateRepository updating;
    private final WorkspaceWriteLockRepository workspaceLocks;
    public ProjectCommandService(DevActorProvider actors, WorkspaceMembershipRepository memberships,
                                 ProjectCreationRepository creation, ProjectRepository projects, ProjectUpdateRepository updating,
                                 WorkspaceWriteLockRepository workspaceLocks) {
        this.actors = actors;
        this.memberships = memberships;
        this.creation = creation;
        this.projects = projects;
        this.updating = updating;
        this.workspaceLocks = workspaceLocks;
    }
    @Transactional(readOnly=false,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public ProjectView update(long workspaceId, long projectId, String name, String description, long version) {
        var actor = actors.current();
        var membership = memberships.findByWorkspace_IdAndUser_Id(workspaceId, actor.userId())
                .orElseThrow(ProjectNotFoundException::new);
        var project = projects.findByIdAndWorkspaceId(projectId, workspaceId)
                .orElseThrow(ProjectNotFoundException::new);
        if (membership.getRole() != WorkspaceRole.OWNER && membership.getRole() != WorkspaceRole.ADMIN) {
            throw new ProjectForbiddenException();
        }
        if (project.isArchived()) throw new ProjectArchivedException();
        if (project.getVersion() != version) throw new ProjectVersionConflictException();
        updating.update(project, name, description);
        return ProjectView.from(project);
    }

    @Transactional(readOnly=false,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public ProjectView setArchived(long workspaceId,long projectId,boolean archived,long version) {
        workspaceLocks.lockForProjectArchive(workspaceId);
        var actor=actors.current();
        var membership=memberships.findByWorkspace_IdAndUser_Id(workspaceId,actor.userId())
                .orElseThrow(ProjectNotFoundException::new);
        var project=projects.findByIdAndWorkspaceId(projectId,workspaceId)
                .orElseThrow(ProjectNotFoundException::new);
        if(membership.getRole()!=WorkspaceRole.OWNER && membership.getRole()!=WorkspaceRole.ADMIN)
            throw new ProjectForbiddenException();
        if(project.getVersion()!=version) throw new ProjectVersionConflictException();
        updating.setArchived(project,archived);
        return ProjectView.from(project);
    }

    @Transactional(readOnly=false,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public ProjectView create(long workspaceId, String name, String description) {
        var actor = actors.current();
        var membership = memberships.findByWorkspace_IdAndUser_Id(workspaceId, actor.userId())
                .orElseThrow(ProjectNotFoundException::new);
        if (membership.getRole() != WorkspaceRole.OWNER && membership.getRole() != WorkspaceRole.ADMIN) {
            throw new ProjectForbiddenException();
        }
        var project = Project.create(workspaceId, name, description);
        creation.persist(project);
        return ProjectView.from(project);
    }
}
