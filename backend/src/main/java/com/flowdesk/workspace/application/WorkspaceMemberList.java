package com.flowdesk.workspace.application;

import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;

import java.util.List;

public record WorkspaceMemberList(List<Item> items, int page, int size, boolean hasNext) {
    public WorkspaceMemberList { items = List.copyOf(items); }

    public record Item(String id, String displayName, String role) {
        static Item from(WorkspaceMembershipRepository.MemberProjection projection) {
            return new Item(projection.getId().toString(), projection.getDisplayName(), projection.getRole());
        }
    }
}
