package com.flowdesk.project.repository;
import com.flowdesk.project.domain.Project;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.*;
@Repository
@Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
public class ProjectCreationRepository {
    private final EntityManager entityManager;
    public ProjectCreationRepository(EntityManager entityManager){this.entityManager=entityManager;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void persist(Project project){entityManager.persist(project);entityManager.flush();}
}
