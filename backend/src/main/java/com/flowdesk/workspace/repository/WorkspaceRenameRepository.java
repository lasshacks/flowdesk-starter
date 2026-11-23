package com.flowdesk.workspace.repository;

import com.flowdesk.workspace.domain.Workspace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
public class WorkspaceRenameRepository {
    @PersistenceContext private EntityManager entityManager;

    public void rename(Workspace workspace, String name) {
        // Also validate a no-op at transaction completion, when there is no dirty UPDATE.
        entityManager.lock(workspace, LockModeType.OPTIMISTIC);
        workspace.rename(name);
        entityManager.flush();
    }
}
