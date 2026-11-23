package com.flowdesk.workspace.repository;

import com.flowdesk.identity.domain.User;
import com.flowdesk.workspace.domain.Workspace;
import com.flowdesk.workspace.domain.WorkspaceMembership;
import com.flowdesk.workspace.domain.WorkspaceRole;
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
public class WorkspaceMembershipCommandRepository {
    private final EntityManager entityManager;

    public WorkspaceMembershipCommandRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    public Optional<WorkspaceMembership> findForUpdate(long workspaceId, long userId) {
        return entityManager.createQuery("""
                select m from WorkspaceMembership m
                where m.workspace.id = :workspaceId and m.user.id = :userId
                """, WorkspaceMembership.class)
                .setParameter("workspaceId", workspaceId).setParameter("userId", userId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst();
    }

    public WorkspaceMembership add(long workspaceId, long userId, WorkspaceRole role) {
        var membership = WorkspaceMembership.join(entityManager.getReference(Workspace.class, workspaceId),
                entityManager.getReference(User.class, userId), role);
        entityManager.persist(membership);
        entityManager.flush();
        return membership;
    }

    public void remove(WorkspaceMembership membership) {
        entityManager.remove(membership);
        entityManager.flush();
    }

    public void flush() { entityManager.flush(); }

    public long ownerCount(long workspaceId) {
        return entityManager.createQuery("""
                select count(m) from WorkspaceMembership m
                where m.workspace.id = :workspaceId and m.role = :role
                """, Long.class)
                .setParameter("workspaceId", workspaceId).setParameter("role", WorkspaceRole.OWNER)
                .getSingleResult();
    }
}
