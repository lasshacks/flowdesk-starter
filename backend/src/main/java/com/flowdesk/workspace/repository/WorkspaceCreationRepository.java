package com.flowdesk.workspace.repository;

import com.flowdesk.identity.domain.User;
import com.flowdesk.workspace.domain.Workspace;
import com.flowdesk.workspace.domain.WorkspaceMembership;
import jakarta.persistence.EntityManager;
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
public class WorkspaceCreationRepository {
    @PersistenceContext private EntityManager entityManager;

    public WorkspaceMembership persist(Workspace workspace, long actorId) {
        entityManager.persist(workspace);
        var owner = WorkspaceMembership.ownerOf(workspace, entityManager.getReference(User.class, actorId));
        entityManager.persist(owner);
        entityManager.flush();
        return owner;
    }
}
