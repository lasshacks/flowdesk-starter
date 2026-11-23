package com.flowdesk.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "workspaces", schema = "flowdesk")
public class Workspace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "name", nullable = false, length = 100)
    private String name;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Workspace() { }
    public static Workspace create(String name) {
        if (name == null) throw new InvalidWorkspaceNameException();
        int start = 0, end = name.length();
        while (start < end && space(name.codePointAt(start))) start += Character.charCount(name.codePointAt(start));
        while (end > start && space(name.codePointBefore(end))) end -= Character.charCount(name.codePointBefore(end));
        String normalized = name.substring(start, end);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 100 || normalized.codePoints().anyMatch(cp ->
                Character.isISOControl(cp) || (cp >= 0xD800 && cp <= 0xDFFF))) {
            throw new InvalidWorkspaceNameException();
        }
        Workspace workspace = new Workspace();
        workspace.name = normalized;
        return workspace;
    }
    public void rename(String name) {
        String normalized = create(name).name;
        if (!this.name.equals(normalized)) {
            this.name = normalized;
            this.updatedAt = Instant.now();
        }
    }
    public Long getVersion() { return version; }
    private static boolean space(int cp) { return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }
    public Long getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
