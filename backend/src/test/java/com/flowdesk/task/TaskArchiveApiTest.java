package com.flowdesk.task;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import static com.flowdesk.workspace.WorkspaceReadApiTest.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TaskArchiveApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("task_archive_api").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static final String PATH="/api/workspaces/1/projects/1/tasks";
    static Map<String,Object> options(String actor){return Map.of("FLOWDESK_DEV_ACTOR_EMAIL",actor+"@example.test",
            "flowdesk.dev.workspace-write-enabled","true","flowdesk.dev.task-archive-enabled","true",
            "FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS","http://127.0.0.1:5173");}

    @BeforeAll static void prepare() throws Exception {
        db=new IsolatedReadDatabase(POSTGRES,true,"11");
        db.grantCreate().success(); db.grantRename().success(); db.grantProject().success(); db.grantProjectUpdate().success(); db.grantProjectArchive().success();
        db.grantTask().success(); db.grantTaskUpdate().success(); db.grantTaskStatusChange().success(); db.grantTaskStatusHistoryRead().success(); db.grantTaskAssigneeDueDate().success();
        db.grantWorkspaceMemberListRead().success(); db.grantWorkspaceMembershipManagement().success();
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Archive parent'); INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Archive me')");
        db.grantTaskArchive().success();
        assertThat(db.grantTaskArchive().success()).contains("TASK_ARCHIVE");
        assertThat(db.grantTaskAssigneeDueDate().success()).contains("TASK_ARCHIVE");
        assertThat(db.grantWorkspaceMembershipManagement().success()).contains("TASK_ARCHIVE");
        assertThat(db.query("SELECT NOT has_table_privilege('flowdesk_app','flowdesk.tasks','UPDATE') AND NOT has_table_privilege('flowdesk_app','flowdesk.tasks','SELECT') AND has_column_privilege('flowdesk_app','flowdesk.tasks','archived_at','SELECT') AND has_column_privilege('flowdesk_app','flowdesk.tasks','archived_at','UPDATE') AND (SELECT count(*)=9 FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE'))")).isEqualTo("t");
        alice=startFor(db,"dev-actor",options("alice"));
    }
    @AfterAll static void close(){if(alice!=null)alice.close();}
    @AfterEach void reset() throws Exception { db.sql("UPDATE flowdesk.projects SET archived_at=NULL,version=0,updated_at=created_at WHERE id=1; UPDATE flowdesk.tasks SET archived_at=NULL,version=0,updated_at=created_at,status='TODO' WHERE id=1; DELETE FROM flowdesk.task_status_activities WHERE task_id=1"); }

    static HttpResponse<String> archive(ConfigurableApplicationContext ctx,boolean archived,String version)throws Exception {
        int port=((ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+PATH+"/1/archive")).timeout(Duration.ofSeconds(10))
                .header("Accept","application/json").header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"archived\":"+archived+",\"version\":\""+version+"\"}"));
        return HTTP.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> patch(ConfigurableApplicationContext ctx,String path,String json)throws Exception {
        int port=((ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
                .header("Accept","application/json").header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(json));
        return HTTP.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void archiveRestoreNoopAndStaleVersion() throws Exception {
        var archived=archive(alice,true,"0"); assertThat(archived.statusCode()).isEqualTo(200);
        assertThat(body(archived).path("archived").asBoolean()).isTrue(); assertThat(body(archived).path("archivedAt").isTextual()).isTrue();
        assertThat(body(archived).path("version").asText()).isEqualTo("1");
        assertThat(body(call(alice,PATH)).path("items").isEmpty()).isTrue();
        assertThat(body(call(alice,PATH+"?includeArchived=true")).path("items").size()).isEqualTo(1);
        assertThat(body(call(alice,PATH+"?includeArchived=true")).path("items").get(0).path("archived").asBoolean()).isTrue();
        assertThat(call(alice,PATH+"?includeArchived=no").statusCode()).isEqualTo(400);
        assertThat(call(alice,PATH+"?includeArchived=true&includeArchived=true").statusCode()).isEqualTo(400);
        assertThat(call(alice,PATH+"/1").statusCode()).isEqualTo(200);
        assertThat(call(alice,PATH+"/1/status-activities").statusCode()).isEqualTo(200);
        problem(archive(alice,false,"0"),409,"TASK_VERSION_CONFLICT");
        assertThat(body(archive(alice,true,"1")).path("version").asText()).isEqualTo("1");
        var restored=archive(alice,false,"1"); assertThat(restored.statusCode()).isEqualTo(200);
        assertThat(body(restored).path("archived").asBoolean()).isFalse(); assertThat(body(restored).path("archivedAt").isNull()).isTrue();
        assertThat(body(restored).path("version").asText()).isEqualTo("2");
        assertThat(db.query("SELECT count(*) FROM flowdesk.task_status_activities WHERE task_id=1")).isEqualTo("0");
    }
    @Test void workspaceAdminCanArchiveAndRestore() throws Exception {
        try(var admin=startFor(db,"dev-actor",options("bob"))) {
            assertThat(archive(admin,true,"0").statusCode()).isEqualTo(200);
            assertThat(body(archive(admin,false,"1")).path("archived").asBoolean()).isFalse();
        }
    }
    @Test void archiveRequiresAdminAndStrictBody() throws Exception {
        try(var member=startFor(db,"dev-actor",options("carol"))){problem(archive(member,true,"0"),403,"TASK_FORBIDDEN");}
        try(var outsider=startFor(db,"dev-actor",options("eve"))){problem(archive(outsider,true,"0"),404,"TASK_NOT_FOUND");}
        for(String invalid:new String[]{"{}","{\"archived\":true}","{\"archived\":1,\"version\":\"0\"}","{\"archived\":true,\"version\":0}","{\"archived\":true,\"version\":\"00\"}","{\"archived\":true,\"version\":\"0\",\"extra\":1}","{\"archived\":true,\"archived\":false,\"version\":\"0\"}"})
            assertThat(patch(alice,PATH+"/1/archive",invalid).statusCode()).isIn(400,413);
    }
    @Test void archivePermissionRunnerRejectsExtraColumnGrantWithoutRepair() throws Exception {
        db.sql("GRANT UPDATE(project_id) ON flowdesk.tasks TO flowdesk_app");
        try {
            assertThat(db.grantTaskArchive().failure()).contains("no automatic repair");
            assertThat(db.query("SELECT has_column_privilege('flowdesk_app','flowdesk.tasks','project_id','UPDATE') AND has_column_privilege('flowdesk_app','flowdesk.tasks','archived_at','UPDATE')")).isEqualTo("t");
        } finally { db.sql("REVOKE UPDATE(project_id) ON flowdesk.tasks FROM flowdesk_app"); }
        assertThat(db.grantTaskArchive().success()).contains("TASK_ARCHIVE");
    }
    @Test void archivedTaskIsReadOnlyAndParentProjectMustBeActive() throws Exception {
        assertThat(archive(alice,true,"0").statusCode()).isEqualTo(200);
        problem(patch(alice,PATH+"/1","{\"title\":\"Updated\",\"description\":null,\"priority\":\"MEDIUM\",\"assigneeId\":null,\"dueDate\":null,\"version\":\"1\"}"),409,"TASK_ARCHIVED");
        problem(patch(alice,PATH+"/1/status","{\"status\":\"DONE\",\"version\":\"1\"}"),409,"TASK_ARCHIVED");
        assertThat(db.query("SELECT title||':'||status||':'||version FROM flowdesk.tasks WHERE id=1")).isEqualTo("Archive me:TODO:1");
        assertThat(db.query("SELECT count(*) FROM flowdesk.task_status_activities WHERE task_id=1")).isEqualTo("0");
        assertThat(archive(alice,false,"1").statusCode()).isEqualTo(200);
        db.sql("UPDATE flowdesk.projects SET archived_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=1");
        problem(archive(alice,true,"2"),409,"PROJECT_ARCHIVED");
    }
}
