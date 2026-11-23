package com.flowdesk.task;

import com.flowdesk.support.IsolatedReadDatabase;
import com.flowdesk.task.application.TaskCommandService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import static com.flowdesk.workspace.WorkspaceReadApiTest.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TaskApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("task_api").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static String parents,original;
    static final String PATH="/api/workspaces/1/projects/1/tasks";
    static Map<String,Object> options(String user){return Map.of("FLOWDESK_DEV_ACTOR_EMAIL",user+"@example.test",
            "flowdesk.dev.workspace-write-enabled","true","FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS","http://127.0.0.1:5173");}
    @BeforeAll static void prepare()throws Exception{
        db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();db.grantTaskStatusHistoryRead().success();db.grantTaskAssigneeDueDate().success();
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name,version) VALUES(1,'Parent',7),(1,'Other project',3),(2,'Foreign project',2)");
        parents=db.query("SELECT json_agg(p ORDER BY id)::text FROM flowdesk.projects p");original=db.snapshot();
        alice=startFor(db,"dev-actor",options("alice"));
    }
    @AfterAll static void close(){if(alice!=null)alice.close();}
    @AfterEach void restore()throws Exception{
        db.sql("DROP TRIGGER IF EXISTS reject_task ON flowdesk.tasks; DROP FUNCTION IF EXISTS flowdesk.reject_task(); DELETE FROM flowdesk.tasks; DELETE FROM flowdesk.workspace_memberships WHERE workspace_id=2 AND user_id=(SELECT id FROM flowdesk.users WHERE email='alice@example.test')");
        assertThat(db.snapshot()).isEqualTo(original);assertThat(db.query("SELECT json_agg(p ORDER BY id)::text FROM flowdesk.projects p")).isEqualTo(parents);
    }
    static HttpResponse<String> post(ConfigurableApplicationContext ctx,String path,HttpRequest.BodyPublisher body,String...headers)throws Exception{
        int port=((ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(body);
        for(int i=0;i<headers.length;i+=2)builder.header(headers[i],headers[i+1]);return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> create(ConfigurableApplicationContext ctx,String path,String json)throws Exception{
        return post(ctx,path,HttpRequest.BodyPublishers.ofString(json),"Origin","http://127.0.0.1:5173");
    }
    static HttpResponse<String> create(String json)throws Exception{return create(alice,PATH,json);}
    @ParameterizedTest @ValueSource(strings={"alice","bob","carol","dave","eve"})
    void inheritedRolesAndResourceBeforeRole(String user)throws Exception{
        db.sql("INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Existing')");String id=db.query("SELECT id FROM flowdesk.tasks");
        try(var ctx=startFor(db,"dev-actor",options(user))){
            var list=call(ctx,PATH);var detail=call(ctx,PATH+"/"+id);var created=create(ctx,PATH,"{\"title\":\"New\"}");
            if(user.equals("eve")){problem(list,404,"TASK_NOT_FOUND");problem(detail,404,"TASK_NOT_FOUND");problem(created,404,"TASK_NOT_FOUND");}
            else{
                assertThat(list.statusCode()).isEqualTo(200);assertThat(body(list).path("items").get(0).size()).isEqualTo(9);
                assertThat(detail.statusCode()).isEqualTo(200);assertThat(body(detail).size()).isEqualTo(12);
                if(user.equals("dave"))problem(created,403,"TASK_FORBIDDEN");else assertThat(created.statusCode()).isEqualTo(201);
            }
            problem(create(ctx,"/api/workspaces/1/projects/3/tasks","{\"title\":\"No\"}"),404,"TASK_NOT_FOUND");
            problem(call(ctx,"/api/workspaces/1/projects/2/tasks/"+id),404,"TASK_NOT_FOUND");
            if(user.equals("carol")){
                problem(create(ctx,"/api/workspaces/1/projects","{\"name\":\"Denied\"}"),403,"PROJECT_FORBIDDEN");
                var patch=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+((ServletWebServerApplicationContext)ctx).getWebServer().getPort()+"/api/workspaces/1/projects/1"))
                        .header("Content-Type","application/json").header("Origin","http://127.0.0.1:5173")
                        .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"name\":\"Denied\",\"description\":null,\"version\":\"7\"}")).build();
                problem(HTTP.send(patch,HttpResponse.BodyHandlers.ofString()),403,"PROJECT_FORBIDDEN");
            }
        }
    }
    @Test void normalizationDefaultsPrioritiesAndDtoLocation()throws Exception{
        var response=create("{\"title\":\"  Task  \",\"description\":\"  line1\\r\\nline2\\tvalue  \",\"priority\":\"HIGH\"}");
        assertThat(response.statusCode()).isEqualTo(201);var dto=body(response);
        assertThat(dto.size()).isEqualTo(12);assertThat(dto.path("title").asText()).isEqualTo("Task");assertThat(dto.path("description").asText()).isEqualTo("line1\nline2\tvalue");
        assertThat(dto.path("assigneeId").isNull()).isTrue();assertThat(dto.path("dueDate").isNull()).isTrue();
        assertThat(dto.path("status").asText()).isEqualTo("TODO");assertThat(dto.path("priority").asText()).isEqualTo("HIGH");
        assertThat(dto.path("version").asText()).isEqualTo("0");
        for(String key:new String[]{"id","workspaceId","projectId","version"})assertThat(dto.path(key).isTextual()).isTrue();
        String location=response.headers().firstValue("Location").orElseThrow();assertThat(location).isEqualTo(PATH+"/"+dto.path("id").asText());
        assertThat(body(call(alice,location))).isEqualTo(dto);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        for(String extra:new String[]{"",",\"description\":null",",\"description\":\" \""}){
            var result=body(create("{\"title\":\"Same\""+extra+"}"));assertThat(result.path("priority").asText()).isEqualTo("MEDIUM");assertThat(result.path("description").isNull()).isTrue();
        }
        for(String priority:new String[]{"LOW","MEDIUM","HIGH"})assertThat(body(create("{\"title\":\"Same\",\"priority\":\""+priority+"\"}")).path("priority").asText()).isEqualTo(priority);
    }
    @Test void currentWorkspaceAssigneeAndCalendarDueDateFlowThroughCreateListAndDetail()throws Exception{
        var response=create("{\"title\":\"Assigned\",\"assigneeId\":\"2\",\"dueDate\":\"2028-02-29\"}");
        assertThat(response.statusCode()).isEqualTo(201);var dto=body(response);
        assertThat(dto.path("assigneeId").asText()).isEqualTo("2");assertThat(dto.path("dueDate").asText()).isEqualTo("2028-02-29");
        assertThat(body(call(alice,PATH+"/"+dto.path("id").asText()))).isEqualTo(dto);
        var item=body(call(alice,PATH)).path("items").get(0);
        assertThat(item.path("assigneeId").asText()).isEqualTo("2");assertThat(item.path("dueDate").asText()).isEqualTo("2028-02-29");
        assertThat(create("{\"title\":\"Out of workspace\",\"assigneeId\":\"5\"}").statusCode()).isEqualTo(400);
        assertThat(create("{\"title\":\"Unknown user\",\"assigneeId\":\"999\"}").statusCode()).isEqualTo(400);
        assertThat(create("{\"title\":\"Viewer assignee\",\"assigneeId\":\"4\"}").statusCode()).isEqualTo(201);
        assertThat(create("{\"title\":\"Clear fields\",\"assigneeId\":null,\"dueDate\":null}").statusCode()).isEqualTo(201);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"title\":42}","{\"title\":null}","{\"title\":\" \"}","{\"title\":\"a\\nB\"}","{\"title\":\"\\uD800\"}",
            "{\"title\":\"x\",\"description\":42}","{\"title\":\"x\",\"description\":\"a\\u0000b\"}","{\"title\":\"x\",\"priority\":null}","{\"title\":\"x\",\"priority\":42}",
            "{\"title\":\"x\",\"priority\":\"low\"}","{\"title\":\"x\",\"priority\":\"URGENT\"}","{\"title\":\"x\",\"status\":\"TODO\"}","{\"title\":\"x\",\"assigneeId\":1}",
            "{\"title\":\"x\",\"dueDate\":\"2026-02-30\"}","{\"title\":\"x\",\"assigneeId\":\"01\"}","{\"title\":\"x\",\"projectId\":2}","{\"title\":\"x\",\"userId\":1}","{\"title\":\"x\",\"title\":\"y\"}","{\"title\":\"x\"} {}","{"})
    void strictInput(String payload)throws Exception{problem(create(payload),400,"INVALID_REQUEST");assertThat(db.query("SELECT count(*) FROM flowdesk.tasks")).isEqualTo("0");}
    @Test void scopePaginationAndBothWorkspaceMemberships()throws Exception{
        db.sql("INSERT INTO flowdesk.tasks(project_id,title) SELECT 1,'T'||n FROM generate_series(1,3) n; INSERT INTO flowdesk.tasks(project_id,title) VALUES(2,'Other'),(3,'Foreign')");
        var first=body(call(alice,PATH+"?size=2"));var second=body(call(alice,PATH+"?size=2&page=1"));
        assertThat(first.path("items").size()).isEqualTo(2);assertThat(first.path("hasNext").asBoolean()).isTrue();assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(body(call(alice,PATH+"?page=10000")).path("items").isEmpty()).isTrue();
        String foreign=db.query("SELECT id FROM flowdesk.tasks WHERE project_id=3");
        problem(call(alice,PATH+"/"+foreign),404,"TASK_NOT_FOUND");
        db.sql("INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) SELECT 2,id,'MEMBER' FROM flowdesk.users WHERE email='alice@example.test'");
        problem(call(alice,"/api/workspaces/1/projects/3/tasks/"+foreign),404,"TASK_NOT_FOUND");
        assertThat(call(alice,"/api/workspaces/2/projects/3/tasks/"+foreign).statusCode()).isEqualTo(200);
        problem(create(alice,"/api/workspaces/2/projects/1/tasks","{\"title\":\"Denied\"}"),404,"TASK_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"?page=-1","?size=101","?page=10001","?page=0&page=0","?sort=title","?status=TODO","/01","/0","/+1","/9223372036854775808","/1?size=1"})
    void invalidQueriesAndIds(String suffix)throws Exception{problem(call(alice,PATH+suffix),400,"INVALID_REQUEST");}
    @Test void unicodeAndRealChunkedBodyLimit()throws Exception{
        String payload=JSON.writer().with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature()).writeValueAsString(Map.of("title","🚀".repeat(200),"description","🚀".repeat(2000)));
        assertThat(create(payload).statusCode()).isEqualTo(201);
        problem(create(JSON.writeValueAsString(Map.of("title","🚀".repeat(201)))),400,"INVALID_REQUEST");
        problem(create(JSON.writeValueAsString(Map.of("title","x","description","🚀".repeat(2001)))),400,"INVALID_REQUEST");
        problem(post(alice,PATH,HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(" ".repeat(32769))),"Origin","http://127.0.0.1:5173"),413,"REQUEST_TOO_LARGE");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void immediateAndCommitFailureRollback(boolean deferred)throws Exception{
        db.sql("CREATE FUNCTION flowdesk.reject_task() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'isolated task failure'; END$$");
        db.sql(deferred?"CREATE CONSTRAINT TRIGGER reject_task AFTER INSERT ON flowdesk.tasks DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_task()":"CREATE TRIGGER reject_task BEFORE INSERT ON flowdesk.tasks FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_task()");
        problem(create("{\"title\":\"Rollback\"}"),500,"INTERNAL_ERROR");assertThat(db.query("SELECT count(*) FROM flowdesk.tasks")).isEqualTo("0");
    }
    @Test void originWriteDisabledAndStartupPermissions()throws Exception{
        problem(post(alice,PATH,HttpRequest.BodyPublishers.ofString("{\"title\":\"No\"}"),"Origin","http://evil.test","X-User-Id","1"),403,"DEV_ACCESS_DENIED");
        problem(post(alice,PATH,HttpRequest.BodyPublishers.ofString("{\"title\":\"No\"}")),403,"DEV_ACCESS_DENIED");
        for(String profile:new String[]{"default","dev-actor"})try(var ctx=startFor(db,profile,Map.of("FLOWDESK_DEV_ACTOR_EMAIL","alice@example.test"))){assertThat(create(ctx,PATH,"{\"title\":\"No\"}").statusCode()).isEqualTo(profile.equals("default")?404:405);}
        db.sql("REVOKE INSERT(assignee_id) ON flowdesk.tasks FROM flowdesk_app");
        try{assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",options("alice"))) {}}).hasStackTraceContaining("tasks read/create privileges");}
        finally{db.sql("GRANT INSERT(assignee_id) ON flowdesk.tasks TO flowdesk_app");}
        db.sql("REVOKE SELECT(assignee_id) ON flowdesk.tasks FROM flowdesk_app");
        try{assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",Map.of("FLOWDESK_DEV_ACTOR_EMAIL","alice@example.test"))) {}}).hasStackTraceContaining("approved Task SELECT access");}
        finally{db.sql("GRANT SELECT(assignee_id) ON flowdesk.tasks TO flowdesk_app");}
    }
    @Test void concurrentDuplicateTitlesCreateIndependentTasks()throws Exception{
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->create("{\"title\":\"Same\"}"));var b=pool.submit(()->create("{\"title\":\"Same\"}"));
            var one=a.get();var two=b.get();assertThat(one.statusCode()).isEqualTo(201);assertThat(two.statusCode()).isEqualTo(201);
            assertThat(body(one).path("id").asText()).isNotEqualTo(body(two).path("id").asText());
        }
    }
}
