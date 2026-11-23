package com.flowdesk.workspace.repository;

import com.flowdesk.workspace.domain.WorkspaceInvitation;
import com.flowdesk.workspace.domain.WorkspaceInvitationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
public class WorkspaceInvitationCommandRepository {
    private final EntityManager entityManager;

    public WorkspaceInvitationCommandRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    public Optional<WorkspaceInvitation> findByDigest(String digest) {
        return entityManager.createQuery("select i from WorkspaceInvitation i where i.tokenDigest = :digest", WorkspaceInvitation.class)
                .setParameter("digest", digest).getResultList().stream().findFirst();
    }

    public Optional<WorkspaceInvitation> findByDigestForUpdate(String digest) {
        return entityManager.createQuery("select i from WorkspaceInvitation i where i.tokenDigest = :digest", WorkspaceInvitation.class)
                .setParameter("digest", digest).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst();
    }

    public Optional<WorkspaceInvitation> findByIdForUpdate(long workspaceId, long invitationId) {
        return entityManager.createQuery("""
                select i from WorkspaceInvitation i where i.workspaceId = :workspaceId and i.id = :id
                """, WorkspaceInvitation.class)
                .setParameter("workspaceId", workspaceId).setParameter("id", invitationId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst();
    }

    public Optional<WorkspaceInvitation> findPendingTargetForUpdate(long workspaceId, long targetUserId) {
        return entityManager.createQuery("""
                select i from WorkspaceInvitation i
                where i.workspaceId = :workspaceId and i.targetUserId = :targetUserId and i.status = :status
                """, WorkspaceInvitation.class)
                .setParameter("workspaceId", workspaceId).setParameter("targetUserId", targetUserId)
                .setParameter("status", WorkspaceInvitationStatus.PENDING)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst();
    }

    public WorkspaceInvitation persist(WorkspaceInvitation invitation) {
        entityManager.persist(invitation);
        entityManager.flush();
        return invitation;
    }

    public void flush() { entityManager.flush(); }
}
