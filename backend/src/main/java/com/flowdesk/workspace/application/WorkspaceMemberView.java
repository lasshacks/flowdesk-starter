package com.flowdesk.workspace.application;

import com.flowdesk.workspace.domain.WorkspaceMembership;
import com.flowdesk.workspace.domain.WorkspaceRole;

public record WorkspaceMemberView(String workspaceId, String id, String displayName, WorkspaceRole role) {
    public static WorkspaceMemberView from(long workspaceId, WorkspaceMembership membership) {
        return new WorkspaceMemberView(Long.toString(workspaceId), membership.getUser().getId().toString(),
                membership.getUser().getDisplayName(), membership.getRole());
    }
}
