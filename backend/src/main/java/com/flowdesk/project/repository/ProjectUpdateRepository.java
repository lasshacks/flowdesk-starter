package com.flowdesk.project.repository;

import com.flowdesk.project.domain.Project;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
@Transactional(propagation=Propagation.MANDATORY)
public class ProjectUpdateRepository {
    private final EntityManager entityManager;
    public ProjectUpdateRepository(EntityManager entityManager) { this.entityManager = entityManager; }
    public void update(Project project, String name, String description) {
        // No-op requests must also validate their read version at transaction completion.
        entityManager.lock(project, LockModeType.OPTIMISTIC);
        project.update(name, description);
        entityManager.flush();
    }
    public void setArchived(Project project, boolean archived) {
        entityManager.lock(project, LockModeType.OPTIMISTIC);
        project.archive(archived);
        entityManager.flush();
    }
}
