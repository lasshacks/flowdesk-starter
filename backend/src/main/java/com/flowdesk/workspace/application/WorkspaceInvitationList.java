package com.flowdesk.workspace.application;

import com.flowdesk.workspace.repository.WorkspaceInvitationReadRepository;

import java.time.Instant;
import java.util.List;

public record WorkspaceInvitationList(List<Item> items, int page, int size, boolean hasNext) {
    public WorkspaceInvitationList { items = List.copyOf(items); }

    public record Item(String id, String targetDisplayName, String targetEmail, String role,
            Instant createdAt, Instant expiresAt, boolean expired) {
        static Item from(WorkspaceInvitationReadRepository.InvitationProjection projection) {
            return new Item(projection.getId().toString(), projection.getTargetDisplayName(),
                    projection.getTargetEmail(), projection.getRole(), projection.getCreatedAt(),
                    projection.getExpiresAt(), Boolean.TRUE.equals(projection.getExpired()));
        }
    }
}
