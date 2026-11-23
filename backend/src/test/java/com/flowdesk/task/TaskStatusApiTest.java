package com.flowdesk.task;

import com.flowdesk.support.IsolatedReadDatabase;
import com.flowdesk.task.application.TaskCommandService;
import com.flowdesk.task.domain.TaskStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.*;
import static com.flowdesk.workspace.WorkspaceReadApiTest.*;
import static com.flowdesk.task.TaskUpdateApiTest.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TaskStatusApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("task_status").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static final String PATH="/api/workspaces/1/projects/1/tasks/1/status";
    static String parents,original;

    @BeforeAll static void prepare() throws Exception {
        db=new IsolatedReadDatabase(POSTGRES,true);
        db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();
        db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();db.grantTaskStatusHistoryRead().success();db.grantTaskAssigneeDueDate().success();
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name,version) VALUES(1,'Parent',7),(2,'Foreign',3); INSERT INTO flowdesk.tasks(project_id,title,description) VALUES(1,'Initial','Original'),(2,'Foreign',NULL)");
        parents=db.snapshot()+db.query("SELECT json_agg(p ORDER BY id)::text FROM flowdesk.projects p");
        original=taskRow();alice=startFor(db,"dev-actor",options("alice"));
    }
    @AfterAll static void close() { if(alice!=null)alice.close(); }
    static String taskRow() throws Exception {return db.query("SELECT row_to_json(t)::text FROM flowdesk.tasks t WHERE id=1");}
    static String count() throws Exception {return db.query("SELECT count(*) FROM flowdesk.task_status_activities");}
    static java.net.http.HttpResponse<String> status(ConfigurableApplicationContext ctx,String target,String version) throws Exception {
        return raw(ctx,PATH,JSON.writeValueAsString(Map.of("status",target,"version",version)));
    }
    @AfterEach void restore() throws Exception {
        db.sql("DROP TRIGGER IF EXISTS reject_status ON flowdesk.tasks; DROP TRIGGER IF EXISTS reject_activity ON flowdesk.task_status_activities; DROP FUNCTION IF EXISTS flowdesk.reject_status(); DELETE FROM flowdesk.task_status_activities; DELETE FROM flowdesk.tasks WHERE id>2; UPDATE flowdesk.tasks SET status='TODO',title='Initial',description='Original',priority='MEDIUM',assignee_id=NULL,due_date=NULL,version=0,updated_at=created_at WHERE id=1; DELETE FROM flowdesk.workspace_memberships WHERE workspace_id=2 AND user_id=1");
        assertThat(taskRow()).isEqualTo(original);assertThat(count()).isEqualTo("0");
        assertThat(db.snapshot()+db.query("SELECT json_agg(p ORDER BY id)::text FROM flowdesk.projects p")).isEqualTo(parents);
    }
    @ParameterizedTest @ValueSource(strings={"alice","bob","carol"})
    void everyDirectedTransitionAndExactAuditUsesServerActor(String user) throws Exception {
        try(var ctx=startFor(db,"dev-actor",options(user))) {
            String previous="TODO";int version=0;
            // Covers all six directed edges once, returning to TODO.
            for(String target:List.of("IN_PROGRESS","DONE","IN_PROGRESS","TODO","DONE","TODO")) {
                String protectedFields=db.query("SELECT jsonb_build_array(project_id,title,description,priority,created_at)::text FROM flowdesk.tasks WHERE id=1");
                var result=patch(ctx,PATH,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("status",target,"version",""+version))),
                        "Origin","http://127.0.0.1:5173","Content-Type","application/json","X-User-Id","999","X-Role","OWNER");
                assertThat(result.statusCode()).isEqualTo(200);version++;
                var dto=body(result);assertThat(dto.size()).isEqualTo(12);
                assertThat(dto.path("version").asText()).isEqualTo(""+version);assertThat(dto.path("status").asText()).isEqualTo(target);
                assertThat(body(call(ctx,"/api/workspaces/1/projects/1/tasks/1"))).isEqualTo(dto);
                assertThat(body(call(ctx,"/api/workspaces/1/projects/1/tasks")).path("items").get(0).size()).isEqualTo(9);
                assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(db.query("SELECT count(*)=1 AND bool_and(a.from_status='"+previous+"' AND a.to_status='"+target+"' AND u.email='"+user+"@example.test' AND a.occurred_at=t.updated_at AND a.task_version=t.version) FROM flowdesk.task_status_activities a JOIN flowdesk.tasks t ON t.id=a.task_id JOIN flowdesk.users u ON u.id=a.actor_user_id WHERE a.task_id=1 AND a.task_version="+version)).isEqualTo("t");
                assertThat(count()).isEqualTo(""+version);
                assertThat(db.query("SELECT jsonb_build_array(project_id,title,description,priority,created_at)::text FROM flowdesk.tasks WHERE id=1")).isEqualTo(protectedFields);
                previous=target;
            }
        }
    }
    @Test void noOpAndStaleSameTargetAndGeneralUpdateHaveCorrectHistory() throws Exception {
        assertThat(status(alice,"TODO","0").statusCode()).isEqualTo(200);assertThat(taskRow()).isEqualTo(original);assertThat(count()).isEqualTo("0");
        assertThat(status(alice,"DONE","0").statusCode()).isEqualTo(200);
        String before=taskRow();assertThat(status(alice,"DONE","1").statusCode()).isEqualTo(200);assertThat(taskRow()).isEqualTo(before);
        problem(status(alice,"DONE","0"),409,"TASK_VERSION_CONFLICT");assertThat(taskRow()).isEqualTo(before);
        assertThat(update(alice,"Changed","Original","1").statusCode()).isEqualTo(200);assertThat(count()).isEqualTo("1");
        assertThat(status(alice,"TODO","2").statusCode()).isEqualTo(200);
        assertThat(db.query("SELECT string_agg(task_version::text,',' ORDER BY task_version) FROM flowdesk.task_status_activities")).isEqualTo("1,3");
    }
    @ParameterizedTest @ValueSource(strings={"dave","eve"})
    void roleScopeParentAndVersionPrecedence(String user) throws Exception {
        try(var ctx=startFor(db,"dev-actor",options(user))) {
            problem(status(ctx,"DONE","99"),user.equals("eve")?404:403,user.equals("eve")?"TASK_NOT_FOUND":"TASK_FORBIDDEN");
            for(String path:List.of("/api/workspaces/1/projects/2/tasks/2/status","/api/workspaces/1/projects/1/tasks/2/status","/api/workspaces/1/projects/1/tasks/999/status","/api/workspaces/999/projects/1/tasks/1/status"))
                problem(raw(ctx,path,"{\"status\":\"DONE\",\"version\":\"99\"}"),404,"TASK_NOT_FOUND");
        }
        assertThat(count()).isEqualTo("0");assertThat(taskRow()).isEqualTo(original);
    }
    @Test void secondMembershipDoesNotPermitWrongParentsOrProjectManagement() throws Exception {
        db.sql("INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(2,1,'VIEWER')");
        problem(raw(alice,"/api/workspaces/2/projects/1/tasks/1/status","{\"status\":\"DONE\",\"version\":\"0\"}"),404,"TASK_NOT_FOUND");
        problem(raw(alice,"/api/workspaces/2/projects/2/tasks/2/status","{\"status\":\"DONE\",\"version\":\"0\"}"),403,"TASK_FORBIDDEN");
        try(var carol=startFor(db,"dev-actor",options("carol"))) {
            assertThat(raw(carol,"/api/workspaces/1/projects/1","{\"name\":\"Denied\",\"description\":null,\"version\":\"7\"}").statusCode()).isEqualTo(403);
        }
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"status\":\"DONE\"}","{\"version\":\"0\"}","{\"status\":null,\"version\":\"0\"}","{\"status\":42,\"version\":\"0\"}","{\"status\":\"DONE\",\"version\":0}","{\"status\":\"DONE\",\"version\":null}","{\"status\":\"DONE\",\"version\":\"0\",\"actorUserId\":1}","{\"status\":\"DONE\",\"status\":\"TODO\",\"version\":\"0\"}","{\"status\":\"DONE\",\"version\":\"0\"} {}"})
    void rejectsInvalidStrictJson(String payload) throws Exception {problem(raw(alice,PATH,payload),400,"INVALID_REQUEST");}
    @ParameterizedTest @ValueSource(strings={"","todo"," DONE","DONE ","UNKNOWN"})
    void rejectsInvalidStatus(String target) throws Exception {problem(status(alice,target,"0"),400,"INVALID_REQUEST");}
    @ParameterizedTest @ValueSource(strings={"","-1","00","01","+1","1.0","9223372036854775808"})
    void rejectsInvalidVersion(String version) throws Exception {problem(status(alice,"DONE",version),400,"INVALID_REQUEST");}
    @ParameterizedTest @ValueSource(strings={"fromStatus","userId","role","workspaceId","projectId","taskId","title","description","priority","occurredAt","task_version"})
    void rejectsEveryClientControlledExtraField(String field) throws Exception {
        problem(raw(alice,PATH,JSON.writeValueAsString(Map.of("status","DONE","version","0",field,"spoof"))),400,"INVALID_REQUEST");
    }
    @Test void transportAndModesRemainClosed() throws Exception {
        String valid="{\"status\":\"DONE\",\"version\":\"0\"}";
        for(String origin:List.of("http://evil.test","null"))
            problem(patch(alice,PATH,HttpRequest.BodyPublishers.ofString(valid),"Origin",origin,"Content-Type","application/json"),403,"DEV_ACCESS_DENIED");
        problem(patch(alice,PATH,HttpRequest.BodyPublishers.ofString(valid),"Content-Type","application/json"),403,"DEV_ACCESS_DENIED");
        problem(patch(alice,PATH,HttpRequest.BodyPublishers.ofString(valid),"Origin","http://127.0.0.1:5173","Content-Type","text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");
        problem(patch(alice,PATH,HttpRequest.BodyPublishers.ofString(valid),"Origin","http://127.0.0.1:5173","Content-Type","application/json","Content-Encoding","gzip"),415,"UNSUPPORTED_MEDIA_TYPE");
        problem(patch(alice,PATH,HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte)0xff}),"Origin","http://127.0.0.1:5173","Content-Type","application/json"),400,"INVALID_REQUEST");
        for(boolean chunked:List.of(false,true)) {
            var bytes=HttpRequest.BodyPublishers.ofString(" ".repeat(32769));
            problem(patch(alice,PATH,chunked?HttpRequest.BodyPublishers.fromPublisher(bytes):bytes,"Origin","http://127.0.0.1:5173","Content-Type","application/json"),413,"REQUEST_TOO_LARGE");
        }
        problem(raw(alice,PATH+"?role=OWNER",valid),400,"INVALID_REQUEST");
        problem(raw(alice,PATH.replace("workspaces/1","workspaces/01"),valid),400,"INVALID_REQUEST");
        for(String profile:List.of("default","dev-actor"))try(var ctx=startFor(db,profile,Map.of("FLOWDESK_DEV_ACTOR_EMAIL","alice@example.test"))) {
            assertThat(raw(ctx,PATH,valid).statusCode()).isEqualTo(profile.equals("default")?404:405);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realParallelStatusAndGeneralTransactionsShareOneVersion(boolean general) throws Exception {
        try(var blocker=db.admin();var pool=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);blocker.createStatement().execute("SELECT id FROM flowdesk.tasks WHERE id=1 FOR UPDATE");
            var a=pool.submit(()->status(alice,"DONE","0"));
            var b=pool.submit(()->general?update(alice,"Racing edit","Original","0"):status(alice,"IN_PROGRESS","0"));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                while(System.nanoTime()<deadline && !db.query("SELECT count(*)>=2 FROM pg_stat_activity WHERE datname=current_database() AND usename='flowdesk_app' AND wait_event_type='Lock' AND lower(query) LIKE '%update%tasks%'").equals("t"))Thread.sleep(20);
                assertThat(db.query("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename='flowdesk_app' AND wait_event_type='Lock' AND lower(query) LIKE '%update%tasks%'")).isEqualTo("2");
            } finally {blocker.rollback();}
            var one=a.get(12,TimeUnit.SECONDS);var two=b.get(12,TimeUnit.SECONDS);
            assertThat(List.of(one.statusCode(),two.statusCode())).containsExactlyInAnyOrder(200,409);
            problem(one.statusCode()==409?one:two,409,"TASK_VERSION_CONFLICT");
            var winner=body(one.statusCode()==200?one:two);
            assertThat(winner.path("version").asText()).isEqualTo("1");
            assertThat(body(call(alice,"/api/workspaces/1/projects/1/tasks/1"))).isEqualTo(winner);
            assertThat(count()).isEqualTo(winner.path("status").asText().equals("TODO")?"0":"1");
        }
    }
    @Test void noOpDetectsInterveningCommitAtTransactionCompletion() {
        var tx=new TransactionTemplate(alice.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(()->tx.execute(ignored->{
            assertThat(alice.getBean(TaskCommandService.class).changeStatus(1,1,1,TaskStatus.TODO,0).version()).isEqualTo("0");
            try {assertThat(status(alice,"DONE","0").statusCode()).isEqualTo(200);}
            catch(Exception failure){throw new RuntimeException(failure);}
            return null;
        })).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void eitherStatusOrGeneralWinnerLeavesExactlyTheCorrespondingAudit(boolean statusWins) throws Exception {
        var tx=new TransactionTemplate(alice.getBean(PlatformTransactionManager.class));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<Future<java.net.http.HttpResponse<String>>>();
            tx.execute(ignored->{
                var service=alice.getBean(TaskCommandService.class);
                if(statusWins)service.changeStatus(1,1,1,TaskStatus.DONE,0);
                else service.update(1,1,1,"General winner","Original",com.flowdesk.task.domain.TaskPriority.MEDIUM,0,null,null);
                pending.set(pool.submit(()->statusWins?update(alice,"Losing edit","Original","0"):status(alice,"DONE","0")));
                try {
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                    while(System.nanoTime()<deadline && !db.query("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename='flowdesk_app' AND wait_event_type='Lock' AND lower(query) LIKE '%update%tasks%')").equals("t"))Thread.sleep(20);
                    assertThat(db.query("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename='flowdesk_app' AND wait_event_type='Lock' AND lower(query) LIKE '%update%tasks%')")).isEqualTo("t");
                } catch(Exception failure){throw new RuntimeException(failure);}
                return null;
            });
            problem(pending.get().get(12,TimeUnit.SECONDS),409,"TASK_VERSION_CONFLICT");
            assertThat(count()).isEqualTo(statusWins?"1":"0");
            assertThat(db.query("SELECT status||'|'||version FROM flowdesk.tasks WHERE id=1")).isEqualTo(statusWins?"DONE|1":"TODO|1");
        }
    }
    @ParameterizedTest @ValueSource(strings={"update","insert","commit"})
    void updateInsertAndDeferredCommitFailureRollbackStatusAndAudit(String stage) throws Exception {
        db.sql("CREATE FUNCTION flowdesk.reject_status() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'isolated status failure'; END$$");
        db.sql(switch(stage) {
            case "update" -> "CREATE TRIGGER reject_status BEFORE UPDATE ON flowdesk.tasks FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_status()";
            case "insert" -> "CREATE TRIGGER reject_activity BEFORE INSERT ON flowdesk.task_status_activities FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_status()";
            default -> "CREATE CONSTRAINT TRIGGER reject_activity AFTER INSERT ON flowdesk.task_status_activities DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_status()";
        });
        problem(status(alice,"DONE","0"),500,"INTERNAL_ERROR");assertThat(taskRow()).isEqualTo(original);assertThat(count()).isEqualTo("0");
    }
    @Test void uniqueAuditFailureDoesNotBecome409OrPartiallyCommit() throws Exception {
        db.sql("INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at) VALUES(1,1,'TODO','IN_PROGRESS',1,CURRENT_TIMESTAMP)");
        problem(status(alice,"DONE","0"),500,"INTERNAL_ERROR");assertThat(taskRow()).isEqualTo(original);assertThat(count()).isEqualTo("1");
    }
    @Test void silentlySkippedActivityInsertStillRollsBackStatus() throws Exception {
        db.sql("CREATE FUNCTION flowdesk.reject_status() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RETURN NULL; END$$; CREATE TRIGGER reject_activity BEFORE INSERT ON flowdesk.task_status_activities FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_status()");
        problem(status(alice,"DONE","0"),500,"INTERNAL_ERROR");assertThat(taskRow()).isEqualTo(original);assertThat(count()).isEqualTo("0");
    }
    @Test void removedInsertPrivilegeRollsBackAndPreventsNewWriterStartup() throws Exception {
        db.sql("REVOKE INSERT(occurred_at) ON flowdesk.task_status_activities FROM flowdesk_app");
        try {
            problem(status(alice,"DONE","0"),500,"INTERNAL_ERROR");assertThat(taskRow()).isEqualTo(original);assertThat(count()).isEqualTo("0");
            assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",options("alice"))) {}}).hasStackTraceContaining("Development workspace writes require approved minimum privileges");
        } finally {db.sql("GRANT INSERT(occurred_at) ON flowdesk.task_status_activities TO flowdesk_app");}
    }
    @Test void activityForeignKeyFailureRollsBackTaskAndMissingTableBlocksDevelopmentReadsAndWrites() throws Exception {
        db.sql("CREATE FUNCTION flowdesk.reject_status() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN NEW.actor_user_id=9223372036854775807; RETURN NEW; END$$; CREATE TRIGGER reject_activity BEFORE INSERT ON flowdesk.task_status_activities FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_status()");
        problem(status(alice,"DONE","0"),500,"INTERNAL_ERROR");assertThat(taskRow()).isEqualTo(original);assertThat(count()).isEqualTo("0");
        db.sql("DROP TRIGGER reject_activity ON flowdesk.task_status_activities; ALTER TABLE flowdesk.task_status_activities RENAME TO unavailable_activities");
        try {
            // Either startup guard may run first; both must reject the missing relation.
            assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",options("alice"))) {}})
                    .hasRootCauseInstanceOf(IllegalStateException.class).hasStackTraceContaining("Development workspace writes require approved minimum privileges");
            assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",Map.of("FLOWDESK_DEV_ACTOR_EMAIL","alice@example.test"))) {}})
                    .hasStackTraceContaining("Task-status-history-read policy");
        } finally {db.sql("ALTER TABLE flowdesk.unavailable_activities RENAME TO task_status_activities");}
    }
    @Test void differentTasksRemainIndependent() throws Exception {
        db.sql("INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Independent')");
        String id=db.query("SELECT id FROM flowdesk.tasks WHERE title='Independent'");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->status(alice,"DONE","0"));
            var b=pool.submit(()->raw(alice,PATH.replace("tasks/1/","tasks/"+id+"/"),"{\"status\":\"IN_PROGRESS\",\"version\":\"0\"}"));
            assertThat(a.get(12,TimeUnit.SECONDS).statusCode()).isEqualTo(200);assertThat(b.get(12,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(count()).isEqualTo("2");
        }
    }
}
