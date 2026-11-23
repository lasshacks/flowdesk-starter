package com.flowdesk.project.application;
import com.flowdesk.project.domain.Project;
import java.time.Instant;
public record ProjectView(String id,String workspaceId,String name,String description,boolean archived,Instant archivedAt,String version) {
    public static ProjectView from(Project p){return new ProjectView(p.getId().toString(),p.getWorkspaceId().toString(),p.getName(),p.getDescription(),p.isArchived(),p.getArchivedAt(),p.getVersion().toString());}
}
