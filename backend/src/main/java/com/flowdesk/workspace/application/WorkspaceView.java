package com.flowdesk.workspace.application;

import com.flowdesk.workspace.domain.WorkspaceMembership;
import com.flowdesk.workspace.domain.WorkspaceRole;

public record WorkspaceView(String id, String name, WorkspaceRole myRole, String version) {
    static WorkspaceView from(WorkspaceMembership membership) {
        var workspace = membership.getWorkspace();
        return new WorkspaceView(workspace.getId().toString(), workspace.getName(), membership.getRole(), workspace.getVersion().toString());
    }
}
