package com.flowdesk.task.application;

import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.project.repository.ProjectRepository;
import com.flowdesk.task.repository.TaskRepository;
import com.flowdesk.task.repository.TaskStatusHistoryRepository;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Profile("dev-actor")
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class TaskStatusHistoryQueryService {
    private final DevActorProvider actors;
    private final WorkspaceMembershipRepository memberships;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final TaskStatusHistoryRepository history;
    public TaskStatusHistoryQueryService(DevActorProvider actors,WorkspaceMembershipRepository memberships,
            ProjectRepository projects,TaskRepository tasks,TaskStatusHistoryRepository history) {
        this.actors=actors;this.memberships=memberships;this.projects=projects;this.tasks=tasks;this.history=history;
    }
    public TaskStatusHistory list(long workspaceId,long projectId,long taskId,Long beforeVersion,int size) {
        memberships.findByWorkspace_IdAndUser_Id(workspaceId,actors.current().userId()).orElseThrow(TaskNotFoundException::new);
        projects.findByIdAndWorkspaceId(projectId,workspaceId).orElseThrow(TaskNotFoundException::new);
        tasks.findByIdAndProjectId(taskId,projectId).orElseThrow(TaskNotFoundException::new);
        var found=history.find(taskId,beforeVersion,size+1);
        boolean hasNext=found.size()>size;
        var items=List.copyOf(found.subList(0,Math.min(size,found.size())));
        return new TaskStatusHistory(Long.toString(workspaceId),Long.toString(projectId),Long.toString(taskId),items,
                beforeVersion==null?null:beforeVersion.toString(),size,hasNext,
                hasNext?items.getLast().taskVersion():null);
    }
}
