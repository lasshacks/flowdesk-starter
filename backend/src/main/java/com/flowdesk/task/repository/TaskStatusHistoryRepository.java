package com.flowdesk.task.repository;

import com.flowdesk.task.application.TaskStatusHistory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Repository
@Profile("dev-actor")
@Transactional(propagation=Propagation.MANDATORY,readOnly=true)
public class TaskStatusHistoryRepository {
    private final EntityManager entityManager;
    public TaskStatusHistoryRepository(EntityManager entityManager) { this.entityManager=entityManager; }

    public List<TaskStatusHistory.Item> find(long taskId,Long beforeVersion,int limit) {
        // Do not join actor memberships: former members' events must remain visible.
        // Format the PostgreSQL timestamptz in UTC explicitly; native temporal Java types vary by driver/dialect.
        String sql="""
                SELECT a.id,a.actor_user_id,u.display_name,a.from_status,a.to_status,a.task_version,
                  to_char(a.occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
                FROM flowdesk.task_status_activities a JOIN flowdesk.users u ON u.id=a.actor_user_id
                WHERE a.task_id=:taskId
                """;
        if(beforeVersion!=null)sql+=" AND a.task_version<:beforeVersion";
        sql+=" ORDER BY a.task_version DESC";
        var query=entityManager.createNativeQuery(sql).setParameter("taskId",taskId).setMaxResults(limit);
        if(beforeVersion!=null)query.setParameter("beforeVersion",beforeVersion);
        @SuppressWarnings("unchecked") List<Object[]> rows=query.getResultList();
        return rows.stream().map(row->new TaskStatusHistory.Item(row[0].toString(),
                new TaskStatusHistory.Actor(row[1].toString(),(String)row[2]),(String)row[3],(String)row[4],
                row[5].toString(),Instant.parse((String)row[6]))).toList();
    }
}
