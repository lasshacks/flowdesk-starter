package com.flowdesk.task.application;

import java.time.Instant;
import java.util.List;

public record TaskStatusHistory(String workspaceId, String projectId, String taskId, List<Item> items,
                                String beforeVersion, int size, boolean hasNext, String nextBeforeVersion) {
    public record Actor(String id, String displayName) { }
    public record Item(String id, Actor actor, String fromStatus, String toStatus,
                       String taskVersion, Instant occurredAt) { }
}
