package com.flowdesk.project.application;
import com.flowdesk.project.domain.Project;
import java.util.List;
public record ProjectList(List<Item> items,int page,int size,boolean hasNext) {
    public record Item(String id,String workspaceId,String name,boolean archived) {
        public static Item from(Project p){return new Item(p.getId().toString(),p.getWorkspaceId().toString(),p.getName(),p.isArchived());}
    }
}
