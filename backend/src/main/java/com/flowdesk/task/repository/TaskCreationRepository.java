package com.flowdesk.task.repository;
import com.flowdesk.task.domain.Task;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.*;
@Repository
@Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
public class TaskCreationRepository {
    private final EntityManager entityManager;
    public TaskCreationRepository(EntityManager entityManager){this.entityManager=entityManager;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void persist(Task task){entityManager.persist(task);entityManager.flush();}
}
