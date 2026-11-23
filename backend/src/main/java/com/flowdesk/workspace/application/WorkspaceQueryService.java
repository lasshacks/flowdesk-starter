package com.flowdesk.workspace.application;

import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("dev-actor")
@Transactional(readOnly = true)
public class WorkspaceQueryService {
    private final DevActorProvider actors;
    private final WorkspaceMembershipRepository memberships;

    public WorkspaceQueryService(DevActorProvider actors, WorkspaceMembershipRepository memberships) {
        this.actors = actors;
        this.memberships = memberships;
    }

    public WorkspaceList list(int page, int size) {
        var actor = actors.current();
        var slice = memberships.findByUser_IdOrderByWorkspace_IdAsc(actor.userId(), PageRequest.of(page, size));
        return new WorkspaceList(slice.getContent().stream().map(WorkspaceView::from).toList(),
                page, size, slice.hasNext());
    }

    public WorkspaceView detail(long workspaceId) {
        var actor = actors.current();
        var membership = memberships.findByWorkspace_IdAndUser_Id(workspaceId, actor.userId())
                .orElseThrow(WorkspaceNotFoundException::new);
        // All four persisted membership roles may read basic workspace information.
        return WorkspaceView.from(membership);
    }

    public WorkspaceMemberList members(long workspaceId, int page, int size) {
        var actor = actors.current();
        memberships.findByWorkspace_IdAndUser_Id(workspaceId, actor.userId())
                .orElseThrow(WorkspaceNotFoundException::new);
        var slice = memberships.findMembersByWorkspaceId(workspaceId, PageRequest.of(page, size));
        return new WorkspaceMemberList(slice.getContent().stream().map(WorkspaceMemberList.Item::from).toList(),
                page, size, slice.hasNext());
    }
}
