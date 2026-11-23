package com.flowdesk.project.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name="projects", schema="flowdesk")
public class Project {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="workspace_id",nullable=false,updatable=false) private Long workspaceId;
    @Column(nullable=false,length=100) private String name;
    @Column(length=2000) private String description;
    @Column(name="created_at",nullable=false,insertable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false,insertable=false,updatable=true) private Instant updatedAt;
    @Column(name="archived_at") private Instant archivedAt;
    @Version @Column(nullable=false) private Long version;
    protected Project() { }
    public Long getVersion() { return version; }
    public void update(String name, String description) {
        String normalizedName = normalizeName(name);
        String normalizedDescription = normalizeDescription(description);
        if (!this.name.equals(normalizedName) || !java.util.Objects.equals(this.description, normalizedDescription)) {
            this.name = normalizedName;
            this.description = normalizedDescription;
            this.updatedAt = Instant.now();
        }
    }
    public static Project create(long workspaceId, String name, String description) {
        if (workspaceId < 1) throw new InvalidProjectInputException();
        var project = new Project();
        project.workspaceId = workspaceId;
        project.name = normalizeName(name);
        project.description = normalizeDescription(description);
        return project;
    }
    public boolean isArchived() { return archivedAt != null; }
    public Instant getArchivedAt() { return archivedAt; }
    public void archive(boolean archived) {
        if (archived == isArchived()) return;
        archivedAt = archived ? Instant.now() : null;
        updatedAt = Instant.now();
    }
    public static String normalizeName(String name) { return normalize(name, 100, false); }
    public static String normalizeDescription(String description) { return normalize(description, 2000, true); }

    private static String normalize(String value, int max, boolean optional) {
        if (value == null) {
            if (optional) return null;
            throw new InvalidProjectInputException();
        }
        if (optional) value = value.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0, end = value.length();
        while (start < end && space(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && space(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        String text = value.substring(start, end);
        int length = text.codePointCount(0, text.length());
        if (length > max || (!optional && length == 0) || text.codePoints().anyMatch(cp ->
                (Character.isISOControl(cp) && !(optional && (cp == '\n' || cp == '\t')))
                        || (cp >= 0xD800 && cp <= 0xDFFF))) {
            throw new InvalidProjectInputException();
        }
        return optional && text.isEmpty() ? null : text;
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp);}
    public Long getId(){return id;}
    public Long getWorkspaceId(){return workspaceId;}
    public String getName(){return name;}
    public String getDescription(){return description;}
    public Instant getCreatedAt(){return createdAt;}
    public Instant getUpdatedAt(){return updatedAt;}
}
