package com.flowdesk.workspace.domain;

import com.flowdesk.identity.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

@Entity
@Table(name = "workspace_memberships", schema = "flowdesk")
@DynamicUpdate
public class WorkspaceMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 16)
    private WorkspaceRole role;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected WorkspaceMembership() { }
    public static WorkspaceMembership ownerOf(Workspace workspace, User user) {
        return join(workspace, user, WorkspaceRole.OWNER);
    }
    public static WorkspaceMembership join(Workspace workspace, User user, WorkspaceRole role) {
        if (role == null) throw new IllegalArgumentException("role");
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.workspace = java.util.Objects.requireNonNull(workspace);
        membership.user = java.util.Objects.requireNonNull(user);
        membership.role = role;
        return membership;
    }
    public void changeRole(WorkspaceRole role) {
        this.role = java.util.Objects.requireNonNull(role);
        this.updatedAt = Instant.now();
    }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public Workspace getWorkspace() { return workspace; }
    public WorkspaceRole getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
