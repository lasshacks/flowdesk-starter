package com.flowdesk.task.repository;

import com.flowdesk.task.domain.Task;
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
public class TaskUpdateRepository {
    private final EntityManager entityManager;
    public TaskUpdateRepository(EntityManager entityManager) { this.entityManager = entityManager; }
    public void update(Task task, String title, String description, com.flowdesk.task.domain.TaskPriority priority,Long assigneeId,java.time.LocalDate dueDate) {
        // No-op requests must also validate their read version at transaction completion.
        entityManager.lock(task, LockModeType.OPTIMISTIC);
        task.update(title, description, priority,assigneeId,dueDate);
        entityManager.flush();
    }
    public void setArchived(Task task,boolean archived) {
        entityManager.lock(task,LockModeType.OPTIMISTIC);
        task.setArchived(archived);
        entityManager.flush();
    }
}
