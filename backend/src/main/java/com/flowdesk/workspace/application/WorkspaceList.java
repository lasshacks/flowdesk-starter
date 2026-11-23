package com.flowdesk.workspace.application;

import java.util.List;

public record WorkspaceList(List<WorkspaceView> items, int page, int size, boolean hasNext) {
    public WorkspaceList { items = List.copyOf(items); }
}
