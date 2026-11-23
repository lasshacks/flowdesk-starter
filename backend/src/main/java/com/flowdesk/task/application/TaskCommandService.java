package com.flowdesk.task.application;
import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import com.flowdesk.workspace.domain.WorkspaceRole;
import com.flowdesk.project.repository.ProjectRepository;
import com.flowdesk.task.repository.TaskCreationRepository;
import com.flowdesk.task.repository.TaskRepository;
import com.flowdesk.task.repository.TaskUpdateRepository;
import com.flowdesk.task.repository.TaskStatusChangeRepository;
import com.flowdesk.workspace.repository.WorkspaceWriteLockRepository;
import com.flowdesk.task.domain.*;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.*;
@Service @Profile("dev-actor")
@ConditionalOnProperty(name="flowdesk.dev.workspace-write-enabled",havingValue="true")
public class TaskCommandService {
    private final DevActorProvider actors;private final WorkspaceMembershipRepository memberships;
    private final ProjectRepository projects;private final TaskCreationRepository creation;
    private final TaskRepository tasks; private final TaskUpdateRepository updating;
    private final TaskStatusChangeRepository statusChanges;
    private final WorkspaceWriteLockRepository workspaceLocks;
    public TaskCommandService(DevActorProvider actors,WorkspaceMembershipRepository memberships,ProjectRepository projects,
                              TaskCreationRepository creation,TaskRepository tasks,TaskUpdateRepository updating,
                              TaskStatusChangeRepository statusChanges, WorkspaceWriteLockRepository workspaceLocks) {
        this.actors=actors;this.memberships=memberships;this.projects=projects;this.creation=creation;
        this.tasks=tasks;this.updating=updating;this.statusChanges=statusChanges;this.workspaceLocks=workspaceLocks;
    }
    @Transactional(readOnly=false,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public TaskView update(long workspaceId,long projectId,long taskId,String title,String description,TaskPriority priority,long version,Long assigneeId,LocalDate dueDate) {
        if(!workspaceLocks.lockForTaskChange(workspaceId))throw new TaskNotFoundException();
        var membership=memberships.findByWorkspace_IdAndUser_Id(workspaceId,actors.current().userId()).orElseThrow(TaskNotFoundException::new);
        var project=projects.findByIdAndWorkspaceId(projectId,workspaceId).orElseThrow(TaskNotFoundException::new);
        var task=tasks.findByIdAndProjectId(taskId,projectId).orElseThrow(TaskNotFoundException::new);
        if(!java.util.Set.of(WorkspaceRole.OWNER,WorkspaceRole.ADMIN,WorkspaceRole.MEMBER).contains(membership.getRole()))throw new TaskForbiddenException();
        if(project.isArchived())throw new TaskProjectArchivedException();
        if(task.isArchived())throw new TaskArchivedException();
        if(task.getVersion()!=version)throw new TaskVersionConflictException();
        // A former member's existing assignment remains valid as retained history. Only a changed non-null
        // assignment must target a member who is current at write time.
        if(!java.util.Objects.equals(task.getAssigneeId(),assigneeId))requireAssigneeMembership(workspaceId,assigneeId);
        updating.update(task,title,description,priority,assigneeId,dueDate);
        return TaskView.from(workspaceId,task);
    }
    @Transactional(readOnly=false,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public TaskView changeStatus(long workspaceId,long projectId,long taskId,TaskStatus target,long version) {
        if(!workspaceLocks.lockForTaskChange(workspaceId))throw new TaskNotFoundException();
        if(target==null)throw new InvalidTaskInputException();
        long actorUserId=actors.current().userId();
        var membership=memberships.findByWorkspace_IdAndUser_Id(workspaceId,actorUserId).orElseThrow(TaskNotFoundException::new);
        var project=projects.findByIdAndWorkspaceId(projectId,workspaceId).orElseThrow(TaskNotFoundException::new);
        var task=tasks.findByIdAndProjectId(taskId,projectId).orElseThrow(TaskNotFoundException::new);
        if(!java.util.Set.of(WorkspaceRole.OWNER,WorkspaceRole.ADMIN,WorkspaceRole.MEMBER).contains(membership.getRole()))throw new TaskForbiddenException();
        if(project.isArchived())throw new TaskProjectArchivedException();
        if(task.isArchived())throw new TaskArchivedException();
        if(task.getVersion()!=version)throw new TaskVersionConflictException();
        statusChanges.change(task,target,actorUserId);
        return TaskView.from(workspaceId,task);
    }
    @Transactional(readOnly=false,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public TaskView create(long workspaceId,long projectId,String title,String description,TaskPriority priority,Long assigneeId,LocalDate dueDate){
        if(!workspaceLocks.lockForTaskChange(workspaceId))throw new TaskNotFoundException();
        var membership=memberships.findByWorkspace_IdAndUser_Id(workspaceId,actors.current().userId()).orElseThrow(TaskNotFoundException::new);
        var project=projects.findByIdAndWorkspaceId(projectId,workspaceId).orElseThrow(TaskNotFoundException::new);
        if(!java.util.Set.of(WorkspaceRole.OWNER,WorkspaceRole.ADMIN,WorkspaceRole.MEMBER).contains(membership.getRole()))throw new TaskForbiddenException();
        if(project.isArchived())throw new TaskProjectArchivedException();
        requireAssigneeMembership(workspaceId,assigneeId);
        var task=Task.create(projectId,title,description,priority,assigneeId,dueDate);creation.persist(task);return TaskView.from(workspaceId,task);
    }
    @Transactional(readOnly=false,isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public TaskView setArchived(long workspaceId,long projectId,long taskId,boolean archived,long version) {
        if(!workspaceLocks.lockForTaskChange(workspaceId))throw new TaskNotFoundException();
        var membership=memberships.findByWorkspace_IdAndUser_Id(workspaceId,actors.current().userId()).orElseThrow(TaskNotFoundException::new);
        var project=projects.findByIdAndWorkspaceId(projectId,workspaceId).orElseThrow(TaskNotFoundException::new);
        var task=tasks.findByIdAndProjectId(taskId,projectId).orElseThrow(TaskNotFoundException::new);
        if(membership.getRole()!=WorkspaceRole.OWNER&&membership.getRole()!=WorkspaceRole.ADMIN)throw new TaskForbiddenException();
        if(project.isArchived())throw new TaskProjectArchivedException();
        if(task.getVersion()!=version)throw new TaskVersionConflictException();
        updating.setArchived(task,archived);
        return TaskView.from(workspaceId,task);
    }
    private void requireAssigneeMembership(long workspaceId,Long assigneeId){
        if(assigneeId!=null&&!memberships.existsByWorkspace_IdAndUser_Id(workspaceId,assigneeId))throw new InvalidTaskInputException();
    }
}
