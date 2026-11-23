package com.flowdesk.workspace.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "workspace_invitations", schema = "flowdesk")
@DynamicUpdate
public class WorkspaceInvitation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;
    @Column(name = "target_user_id", nullable = false, updatable = false)
    private Long targetUserId;
    @Column(name = "invited_by_user_id", nullable = false, updatable = false)
    private Long invitedByUserId;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 16, updatable = false)
    private WorkspaceRole role;
    @Column(name = "token_digest", nullable = false, length = 64)
    private String tokenDigest;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private WorkspaceInvitationStatus status;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    protected WorkspaceInvitation() { }

    public static WorkspaceInvitation pending(long workspaceId, long targetUserId, long invitedByUserId,
            WorkspaceRole role, String tokenDigest, Instant expiresAt) {
        if (workspaceId < 1 || targetUserId < 1 || invitedByUserId < 1 || role == null
                || role == WorkspaceRole.OWNER || tokenDigest == null || !tokenDigest.matches("[a-f0-9]{64}")
                || expiresAt == null) throw new IllegalArgumentException("invitation");
        var invitation = new WorkspaceInvitation();
        invitation.workspaceId = workspaceId;
        invitation.targetUserId = targetUserId;
        invitation.invitedByUserId = invitedByUserId;
        invitation.role = role;
        invitation.tokenDigest = tokenDigest;
        invitation.status = WorkspaceInvitationStatus.PENDING;
        invitation.expiresAt = expiresAt;
        return invitation;
    }

    public void revoke() {
        if (status != WorkspaceInvitationStatus.PENDING) throw new IllegalStateException("not pending");
        status = WorkspaceInvitationStatus.REVOKED;
    }

    public void accept(Instant now) {
        if (status != WorkspaceInvitationStatus.PENDING || now == null || !now.isBefore(expiresAt))
            throw new IllegalStateException("not usable");
        status = WorkspaceInvitationStatus.ACCEPTED;
        acceptedAt = now;
    }

    public Long getId() { return id; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getTargetUserId() { return targetUserId; }
    public Long getInvitedByUserId() { return invitedByUserId; }
    public WorkspaceRole getRole() { return role; }
    public String getTokenDigest() { return tokenDigest; }
    public WorkspaceInvitationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getAcceptedAt() { return acceptedAt; }
}
