package com.flowdesk.task.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.DynamicUpdate;

@Entity @Table(name="tasks",schema="flowdesk") @DynamicUpdate
public class Task {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="project_id",nullable=false,updatable=false) private Long projectId;
    @Column(name="assignee_id") private Long assigneeId;
    @Column(name="due_date") private LocalDate dueDate;
    @Version @Column(nullable=false) private Long version;
    @Column(nullable=false,length=200) private String title;
    @Column(length=2000) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16,updatable=false) private TaskStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=6) private TaskPriority priority;
    @Column(name="created_at",nullable=false,insertable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false,insertable=false) private Instant updatedAt;
    @Column(name="archived_at",insertable=false) private Instant archivedAt;
    protected Task() { }
    public static Task create(long projectId,String title,String description,TaskPriority priority,Long assigneeId,LocalDate dueDate) {
        if(projectId<1 || priority==null || (assigneeId!=null&&assigneeId<1))throw new InvalidTaskInputException();
        var task=new Task();task.projectId=projectId;task.title=normalizeTitle(title);
        task.description=normalizeDescription(description);task.status=TaskStatus.TODO;task.priority=priority;
        task.assigneeId=assigneeId;task.dueDate=dueDate;return task;
    }
    public static String normalizeTitle(String title){return normalize(title,200,false);}
    public static String normalizeDescription(String description){return normalize(description,2000,true);}
    public static TaskPriority priority(String value){
        try{return value==null?TaskPriority.MEDIUM:TaskPriority.valueOf(value);}
        catch(IllegalArgumentException failure){throw new InvalidTaskInputException();}
    }
    private static String normalize(String value, int max, boolean optional) {
        if (value == null) {
            if (optional) return null;
            throw new InvalidTaskInputException();
        }
        if (optional) value = value.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0, end = value.length();
        while (start < end && space(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && space(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        String text = value.substring(start, end);
        int length = text.codePointCount(0, text.length());
        if (length > max || (!optional && length == 0) || text.codePoints().anyMatch(cp ->
                (Character.isISOControl(cp) && !(optional && (cp == '\n' || cp == '\t')))
                        || (cp >= 0xD800 && cp <= 0xDFFF))) {
            throw new InvalidTaskInputException();
        }
        return optional && text.isEmpty() ? null : text;
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp);}
    public void update(String title, String description, TaskPriority priority,Long assigneeId,LocalDate dueDate) {
        if (priority == null || (assigneeId!=null&&assigneeId<1)) throw new InvalidTaskInputException();
        String normalizedTitle = normalizeTitle(title);
        String normalizedDescription = normalizeDescription(description);
        if (!this.title.equals(normalizedTitle) || !java.util.Objects.equals(this.description, normalizedDescription)
                || this.priority != priority || !java.util.Objects.equals(this.assigneeId,assigneeId)
                || !java.util.Objects.equals(this.dueDate,dueDate)) {
            this.title = normalizedTitle;
            this.description = normalizedDescription;
            this.priority = priority;
            this.assigneeId=assigneeId;
            this.dueDate=dueDate;
            this.updatedAt = Instant.now();
        }
    }
    public void setArchived(boolean archived) {
        if (archived != (archivedAt != null)) {
            archivedAt = archived ? Instant.now() : null;
            updatedAt = Instant.now();
        }
    }
    public Long getVersion(){return version;}
    public Long getId(){return id;}
    public Long getProjectId(){return projectId;}
    public Long getAssigneeId(){return assigneeId;}
    public LocalDate getDueDate(){return dueDate;}
    public String getTitle(){return title;}
    public String getDescription(){return description;}
    public TaskStatus getStatus(){return status;}
    public TaskPriority getPriority(){return priority;}
    public Instant getCreatedAt(){return createdAt;}
    public Instant getUpdatedAt(){return updatedAt;}
    public Instant getArchivedAt(){return archivedAt;}
    public boolean isArchived(){return archivedAt!=null;}
}
