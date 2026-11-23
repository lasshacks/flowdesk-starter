package com.flowdesk.project.application;
import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import com.flowdesk.project.repository.ProjectRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Profile("dev-actor") @Transactional(readOnly=true)
public class ProjectQueryService {
    private final DevActorProvider actors;
    private final WorkspaceMembershipRepository memberships;
    private final ProjectRepository projects;
    public ProjectQueryService(DevActorProvider actors, WorkspaceMembershipRepository memberships,
                               ProjectRepository projects) {
        this.actors = actors;
        this.memberships = memberships;
        this.projects = projects;
    }

    private void requireMembership(long workspaceId) {
        memberships.findByWorkspace_IdAndUser_Id(workspaceId, actors.current().userId())
                .orElseThrow(ProjectNotFoundException::new);
    }

    public ProjectList list(long workspaceId, int page, int size, boolean includeArchived) {
        requireMembership(workspaceId);
        var slice = includeArchived
                ? projects.findByWorkspaceIdOrderByIdAsc(workspaceId, PageRequest.of(page, size))
                : projects.findByWorkspaceIdAndArchivedAtIsNullOrderByIdAsc(workspaceId, PageRequest.of(page, size));
        return new ProjectList(slice.getContent().stream().map(ProjectList.Item::from).toList(),
                page, size, slice.hasNext());
    }
    public ProjectView detail(long workspaceId, long projectId) {
        requireMembership(workspaceId);
        return projects.findByIdAndWorkspaceId(projectId, workspaceId)
                .map(ProjectView::from).orElseThrow(ProjectNotFoundException::new);
    }
}
