package com.flowdesk.workspace.repository;

import com.flowdesk.workspace.application.WorkspaceNotFoundException;
import com.flowdesk.workspace.domain.Workspace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
public class WorkspaceWriteLockRepository {
    private final EntityManager entityManager;

    public WorkspaceWriteLockRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    public Workspace lockForMembershipChange(long workspaceId) {
        return lock(workspaceId, LockModeType.PESSIMISTIC_WRITE);
    }

    public Workspace lockForProjectArchive(long workspaceId) {
        return lock(workspaceId, LockModeType.PESSIMISTIC_WRITE);
    }

    public boolean lockForTaskChange(long workspaceId) {
        return entityManager.find(Workspace.class, workspaceId, LockModeType.PESSIMISTIC_READ) != null;
    }

    private Workspace lock(long workspaceId, LockModeType mode) {
        var workspace = entityManager.find(Workspace.class, workspaceId, mode);
        if (workspace == null) throw new WorkspaceNotFoundException();
        return workspace;
    }
}
