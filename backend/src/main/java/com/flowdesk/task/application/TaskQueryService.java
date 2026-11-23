package com.flowdesk.task.application;
import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import com.flowdesk.project.repository.ProjectRepository;
import com.flowdesk.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
@Service @Profile("dev-actor") @Transactional(readOnly=true)
public class TaskQueryService {
    private final DevActorProvider actors;private final WorkspaceMembershipRepository memberships;
    private final ProjectRepository projects;private final TaskRepository tasks;
    public TaskQueryService(DevActorProvider actors,WorkspaceMembershipRepository memberships,ProjectRepository projects,TaskRepository tasks){this.actors=actors;this.memberships=memberships;this.projects=projects;this.tasks=tasks;}
    private void requireParent(long workspaceId,long projectId){
        memberships.findByWorkspace_IdAndUser_Id(workspaceId,actors.current().userId()).orElseThrow(TaskNotFoundException::new);
        projects.findByIdAndWorkspaceId(projectId,workspaceId).orElseThrow(TaskNotFoundException::new);
    }
    public TaskList list(long workspaceId,long projectId,int page,int size,boolean includeArchived){
        requireParent(workspaceId,projectId);var request=PageRequest.of(page,size);
        var slice=includeArchived?tasks.findByProjectIdOrderByIdAsc(projectId,request):tasks.findByProjectIdAndArchivedAtIsNullOrderByIdAsc(projectId,request);
        return new TaskList(slice.getContent().stream().map(t->TaskList.Item.from(workspaceId,t)).toList(),page,size,slice.hasNext());
    }
    public TaskView detail(long workspaceId,long projectId,long taskId){
        requireParent(workspaceId,projectId);return tasks.findByIdAndProjectId(taskId,projectId).map(t->TaskView.from(workspaceId,t)).orElseThrow(TaskNotFoundException::new);
    }
}
