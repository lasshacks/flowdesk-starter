package com.flowdesk.task.repository;

import com.flowdesk.task.application.TaskVersionConflictException;
import com.flowdesk.task.domain.Task;
import com.flowdesk.task.domain.TaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Repository
@Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
@Transactional(propagation=Propagation.MANDATORY)
public class TaskStatusChangeRepository {
    private final EntityManager entityManager;
    public TaskStatusChangeRepository(EntityManager entityManager) { this.entityManager=entityManager; }

    public void change(Task task,TaskStatus target,long actorUserId) {
        if(task.getStatus()==target) {
            entityManager.lock(task,LockModeType.OPTIMISTIC);
            return;
        }
        var previous=task.getStatus();
        // Native SQL bypasses @Version: compare/increment/check the affected count explicitly.
        // Keep the managed Task clean; otherwise an automatic flush could perform another update.
        int changed=entityManager.createNativeQuery("""
                UPDATE flowdesk.tasks SET status=:status,updated_at=:time,version=version+1
                WHERE id=:id AND version=:version
                """)
                .setParameter("status",target.name()).setParameter("time",Instant.now())
                .setParameter("id",task.getId()).setParameter("version",task.getVersion()).executeUpdate();
        if(changed!=1) throw new TaskVersionConflictException();
        entityManager.refresh(task);
        // Use the stored timestamp (PostgreSQL microsecond precision) and version after refresh.
        // No generated keys or RETURNING: the application needs no activity SELECT/sequence access.
        int recorded=entityManager.createNativeQuery("""
                INSERT INTO flowdesk.task_status_activities
                  (task_id,actor_user_id,from_status,to_status,task_version,occurred_at)
                VALUES (:task,:actor,:previous,:target,:version,:time)
                """)
                .setParameter("task",task.getId()).setParameter("actor",actorUserId)
                .setParameter("previous",previous.name()).setParameter("target",target.name())
                .setParameter("version",task.getVersion()).setParameter("time",task.getUpdatedAt()).executeUpdate();
        if(recorded!=1) throw new IllegalStateException("Task status activity was not recorded.");
    }
}
