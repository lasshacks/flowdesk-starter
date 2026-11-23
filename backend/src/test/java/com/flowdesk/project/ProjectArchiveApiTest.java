package com.flowdesk.project;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
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
class ProjectArchiveApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("project_archive_api").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static Map<String,Object> options(String user) { return Map.of("FLOWDESK_DEV_ACTOR_EMAIL",user+"@example.test",
            "flowdesk.dev.workspace-write-enabled","true","flowdesk.dev.project-archive-enabled","true",
            "FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS","http://127.0.0.1:5173"); }
    @BeforeAll static void prepare() throws Exception {
        db=new IsolatedReadDatabase(POSTGRES,true); db.grantCreate().success(); db.grantRename().success(); db.grantProject().success();
        db.grantProjectUpdate().success(); db.grantProjectArchive().success(); db.grantTask().success(); db.grantTaskUpdate().success();
        db.grantTaskStatusChange().success(); db.grantTaskStatusHistoryRead().success(); db.grantTaskAssigneeDueDate().success();
        assertThat(db.query("SELECT string_agg(attname,',' ORDER BY attname) FROM pg_attribute WHERE attrelid='flowdesk.projects'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE')"))
                .isEqualTo("archived_at,description,name,updated_at,version");
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Archive target'); INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Keep task')");
        alice=startFor(db,"dev-actor",options("alice"));
    }
    @AfterAll static void close() { if(alice!=null) alice.close(); }
    @AfterEach void reset() throws Exception { db.sql("DELETE FROM flowdesk.tasks WHERE project_id=1 AND id<>1; UPDATE flowdesk.projects SET archived_at=NULL,version=0,updated_at=created_at WHERE name='Archive target'; UPDATE flowdesk.tasks SET status='TODO',version=0,updated_at=created_at WHERE project_id=1"); }
    static HttpResponse<String> patch(ConfigurableApplicationContext ctx,String projectId,String body,String...headers)throws Exception {
        int port=((ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/workspaces/1/projects/"+projectId+"/archive"))
                .timeout(Duration.ofSeconds(10)).header("Accept","application/json").method("PATCH",HttpRequest.BodyPublishers.ofString(body));
        for(int i=0;i<headers.length;i+=2) request.header(headers[i],headers[i+1]);
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> archive(ConfigurableApplicationContext ctx,String projectId,boolean archived,String version)throws Exception {
        return patch(ctx,projectId,"{\"archived\":"+archived+",\"version\":\""+version+"\"}","Origin","http://127.0.0.1:5173","Content-Type","application/json");
    }
    static HttpResponse<String> patchPath(ConfigurableApplicationContext ctx,String path,String body)throws Exception {
        int port=((ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
                .header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json").header("Accept","application/json")
                .method("PATCH",HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void archiveRestoreVersionAndReadVisibility() throws Exception {
        String id=db.query("SELECT id FROM flowdesk.projects WHERE name='Archive target'");
        var archived=archive(alice,id,true,"0"); assertThat(archived.statusCode()).isEqualTo(200);
        assertThat(body(archived).path("archived").asBoolean()).isTrue(); assertThat(body(archived).path("archivedAt").isTextual()).isTrue();
        assertThat(body(archived).path("version").asText()).isEqualTo("1");
        assertThat(body(call(alice,"/api/workspaces/1/projects")).path("items").isEmpty()).isTrue();
        assertThat(body(call(alice,"/api/workspaces/1/projects?includeArchived=true")).path("items").size()).isEqualTo(1);
        assertThat(call(alice,"/api/workspaces/1/projects?includeArchived=yes").statusCode()).isEqualTo(400);
        assertThat(call(alice,"/api/workspaces/1/projects/"+id).statusCode()).isEqualTo(200);
        var stale=archive(alice,id,false,"0"); problem(stale,409,"PROJECT_VERSION_CONFLICT");
        var noOp=archive(alice,id,true,"1"); assertThat(body(noOp).path("version").asText()).isEqualTo("1");
        var restored=archive(alice,id,false,"1"); assertThat(restored.statusCode()).isEqualTo(200);
        assertThat(body(restored).path("archived").asBoolean()).isFalse(); assertThat(body(restored).path("archivedAt").isNull()).isTrue();
        assertThat(body(restored).path("version").asText()).isEqualTo("2");
    }
    @Test void nonAdminCannotArchiveAndArchivedProjectRejectsTaskCreation() throws Exception {
        String id=db.query("SELECT id FROM flowdesk.projects WHERE name='Archive target'");
        try(var member=startFor(db,"dev-actor",options("carol"))) {
            problem(archive(member,id,true,"0"),403,"PROJECT_FORBIDDEN");
        }
        assertThat(archive(alice,id,true,"0").statusCode()).isEqualTo(200);
        var create=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+((ServletWebServerApplicationContext)alice).getWebServer().getPort()+"/api/workspaces/1/projects/"+id+"/tasks"))
                .header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"blocked\"}"));
        var response=HttpClient.newHttpClient().send(create.build(),HttpResponse.BodyHandlers.ofString());
        problem(response,409,"PROJECT_ARCHIVED");
        assertThat(db.query("SELECT count(*) FROM flowdesk.tasks WHERE project_id="+id)).isEqualTo("1");
        assertThat(db.query("SELECT status||':'||version FROM flowdesk.tasks WHERE id=1")).isEqualTo("TODO:0");
    }
    @Test void archivedProjectBlocksRenameTaskUpdateAndStatusChangeButKeepsReads() throws Exception {
        String id=db.query("SELECT id FROM flowdesk.projects WHERE name='Archive target'");
        assertThat(archive(alice,id,true,"0").statusCode()).isEqualTo(200);
        problem(patchPath(alice,"/api/workspaces/1/projects/"+id,"{\"name\":\"Renamed\",\"description\":null,\"version\":\"1\"}"),409,"PROJECT_ARCHIVED");
        String taskPath="/api/workspaces/1/projects/"+id+"/tasks/1";
        problem(patchPath(alice,taskPath,"{\"title\":\"Changed\",\"description\":null,\"priority\":\"HIGH\",\"assigneeId\":null,\"dueDate\":null,\"version\":\"0\"}"),409,"PROJECT_ARCHIVED");
        problem(patchPath(alice,taskPath+"/status","{\"status\":\"DONE\",\"version\":\"0\"}"),409,"PROJECT_ARCHIVED");
        assertThat(call(alice,"/api/workspaces/1/projects/"+id+"/tasks/1").statusCode()).isEqualTo(200);
        assertThat(call(alice,taskPath+"/status-activities").statusCode()).isEqualTo(200);
        assertThat(db.query("SELECT count(*) FROM flowdesk.task_status_activities WHERE task_id=1")).isEqualTo("0");
    }
    @Test void archiveSerializesWithConcurrentTaskCreation() throws Exception {
        String id=db.query("SELECT id FROM flowdesk.projects WHERE name='Archive target'");
        int port=((ServletWebServerApplicationContext)alice).getWebServer().getPort();
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var task=pool.submit(()->{ start.await(); var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/workspaces/1/projects/"+id+"/tasks"))
                    .timeout(Duration.ofSeconds(12)).header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Concurrent task\"}")); return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString()); });
            var archive=pool.submit(()->{ start.await(); return archive(alice,id,true,"0"); });
            start.countDown();
            var taskResult=task.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var archiveResult=archive.get(20,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(archiveResult.statusCode()).isEqualTo(200);
            if(taskResult.statusCode()==409) problem(taskResult,409,"PROJECT_ARCHIVED");
            else assertThat(taskResult.statusCode()).isEqualTo(201);
            assertThat(body(call(alice,"/api/workspaces/1/projects/"+id)).path("archived").asBoolean()).isTrue();
        } finally { pool.shutdownNow(); }
    }
}
