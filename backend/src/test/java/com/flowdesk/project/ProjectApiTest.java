package com.flowdesk.project;

import com.flowdesk.support.IsolatedReadDatabase;
import com.flowdesk.project.application.ProjectCommandService;
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
class ProjectApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("project_api").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static String original;
    static Map<String,Object> options(String user){return Map.of("FLOWDESK_DEV_ACTOR_EMAIL",user+"@example.test",
            "flowdesk.dev.workspace-write-enabled","true","FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS","http://127.0.0.1:5173");}
    @BeforeAll static void prepare()throws Exception{
        db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();db.grantTaskStatusHistoryRead().success();db.grantTaskAssigneeDueDate().success();
        original=db.snapshot();alice=startFor(db,"dev-actor",options("alice"));
    }
    @AfterAll static void close(){if(alice!=null)alice.close();}
    @AfterEach void restore()throws Exception{
        db.sql("DROP TRIGGER IF EXISTS reject_project ON flowdesk.projects; DROP FUNCTION IF EXISTS flowdesk.reject_project(); DELETE FROM flowdesk.projects; DELETE FROM flowdesk.workspace_memberships WHERE workspace_id=2 AND user_id=(SELECT id FROM flowdesk.users WHERE email='alice@example.test')");
        assertThat(db.snapshot()).isEqualTo(original);
    }
    static HttpResponse<String> post(ConfigurableApplicationContext ctx,String path,HttpRequest.BodyPublisher body,String...headers)throws Exception{
        int port=((ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").POST(body);
        for(int i=0;i<headers.length;i+=2)builder.header(headers[i],headers[i+1]);
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> create(ConfigurableApplicationContext ctx,String path,String json)throws Exception{
        return post(ctx,path,HttpRequest.BodyPublishers.ofString(json),"Origin","http://127.0.0.1:5173");
    }
    static HttpResponse<String> create(String json)throws Exception{return create(alice,"/api/workspaces/1/projects",json);}
    @ParameterizedTest @ValueSource(strings={"alice","bob","carol","dave","eve"})
    void rolesAreCheckedInParentWorkspace(String user)throws Exception{
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name,description) VALUES(1,'Existing','Plain <b>text</b>')");
        String id=db.query("SELECT id FROM flowdesk.projects");
        try(var ctx=startFor(db,"dev-actor",options(user))){
            var list=call(ctx,"/api/workspaces/1/projects");
            var detail=call(ctx,"/api/workspaces/1/projects/"+id);
            var created=create(ctx,"/api/workspaces/1/projects","{\"name\":\"New\"}");
            if(user.equals("eve")){
                problem(list,404,"PROJECT_NOT_FOUND");problem(detail,404,"PROJECT_NOT_FOUND");problem(created,404,"PROJECT_NOT_FOUND");
            }else{
                assertThat(list.statusCode()).isEqualTo(200);assertThat(body(list).path("items").get(0).size()).isEqualTo(4);
                assertThat(detail.statusCode()).isEqualTo(200);assertThat(body(detail).path("description").asText()).isEqualTo("Plain <b>text</b>");
                if(user.equals("alice")||user.equals("bob"))assertThat(created.statusCode()).isEqualTo(201);
                else problem(created,403,"PROJECT_FORBIDDEN");
            }
        }
    }
    @Test void normalizesDtoLocationNullableAndUnicodeBoundaries()throws Exception{
        var response=create("{\"name\":\"  Project  \",\"description\":\"  line1\\r\\nline2\\tvalue  \"}");
        assertThat(response.statusCode()).isEqualTo(201);var dto=body(response);
        assertThat(dto.size()).isEqualTo(7);assertThat(dto.path("version").asText()).isEqualTo("0");assertThat(dto.path("name").asText()).isEqualTo("Project");assertThat(dto.path("archived").asBoolean()).isFalse();assertThat(dto.path("archivedAt").isNull()).isTrue();
        assertThat(dto.path("workspaceId").asText()).isEqualTo("1");assertThat(dto.path("id").isTextual()).isTrue();
        assertThat(dto.path("description").asText()).isEqualTo("line1\nline2\tvalue");
        String location=response.headers().firstValue("Location").orElseThrow();assertThat(location).isEqualTo("/api/workspaces/1/projects/"+dto.path("id").asText());
        assertThat(body(call(alice,location))).isEqualTo(dto);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        for(String description:new String[]{"",",\"description\":null",",\"description\":\"  \""}) {
            assertThat(body(create("{\"name\":\"Nullable\""+description+"}")).path("description").isNull()).isTrue();
        }
        String payload=JSON.writer().with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature())
                .writeValueAsString(Map.of("name","🚀".repeat(100),"description","🚀".repeat(2000)));
        assertThat(payload.length()).isGreaterThan(24000);assertThat(create(payload).statusCode()).isEqualTo(201);
        problem(create(JSON.writeValueAsString(Map.of("name","🚀".repeat(101)))),400,"INVALID_REQUEST");
        problem(create(JSON.writeValueAsString(Map.of("name","x","description","🚀".repeat(2001)))),400,"INVALID_REQUEST");
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"name\":42}","{\"name\":\" \"}","{\"name\":\"x\\nY\"}",
            "{\"name\":\"x\",\"description\":42}","{\"name\":\"x\",\"description\":\"a\\u0000b\"}","{\"name\":\"\\uD800\"}",
            "{\"name\":\"x\",\"workspaceId\":2}","{\"name\":\"x\",\"role\":\"OWNER\"}","{\"name\":\"x\",\"userId\":1}",
            "{\"name\":\"x\",\"name\":\"y\"}","{\"name\":\"x\"} {}","{"})
    void strictInput(String payload)throws Exception{problem(create(payload),400,"INVALID_REQUEST");assertThat(db.query("SELECT count(*) FROM flowdesk.projects")).isEqualTo("0");}
    @ParameterizedTest @ValueSource(strings={"?page=-1","?page=10001","?size=0","?size=101","?page=0&page=0","?sort=name","?userId=1","/01","/0","/+1","/9223372036854775808","/1?size=1"})
    void rejectsBadQueriesAndIds(String suffix)throws Exception{problem(call(alice,"/api/workspaces/1/projects"+suffix),400,"INVALID_REQUEST");}
    @Test void scopePaginationEmptyAndMismatchEvenWhenMemberOfBoth()throws Exception{
        assertThat(body(call(alice,"/api/workspaces/1/projects")).path("items").isEmpty()).isTrue();
        problem(call(alice,"/api/workspaces/2/projects"),404,"PROJECT_NOT_FOUND");
        problem(call(alice,"/api/workspaces/9999/projects"),404,"PROJECT_NOT_FOUND");
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'One'),(1,'Two'),(1,'Three'),(2,'Foreign'); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) SELECT 2,id,'VIEWER' FROM flowdesk.users WHERE email='alice@example.test'");
        String foreign=db.query("SELECT id FROM flowdesk.projects WHERE workspace_id=2");
        assertThat(call(alice,"/api/workspaces/2/projects/"+foreign).statusCode()).isEqualTo(200);
        problem(call(alice,"/api/workspaces/1/projects/"+foreign),404,"PROJECT_NOT_FOUND");
        var first=body(call(alice,"/api/workspaces/1/projects?size=2"));assertThat(first.path("items").size()).isEqualTo(2);assertThat(first.path("hasNext").asBoolean()).isTrue();
        var second=body(call(alice,"/api/workspaces/1/projects?page=1&size=2"));assertThat(second.path("items").size()).isEqualTo(1);assertThat(second.path("hasNext").asBoolean()).isFalse();
        assertThat(body(call(alice,"/api/workspaces/1/projects?page=10000")).path("items").isEmpty()).isTrue();
        problem(create("{\"name\":\"x\"}"+" ".repeat(32768)),413,"REQUEST_TOO_LARGE");
        var chunked=HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(" ".repeat(32769)));
        problem(post(alice,"/api/workspaces/1/projects",chunked,"Origin","http://127.0.0.1:5173"),413,"REQUEST_TOO_LARGE");
        problem(create(alice,"/api/workspaces/01/projects","{\"name\":\"x\"}"),400,"INVALID_REQUEST");
        problem(create(alice,"/api/workspaces/1/projects?role=OWNER","{\"name\":\"x\"}"),400,"INVALID_REQUEST");
    }
    @Test void originAndActorHeadersCannotBypassBoundary()throws Exception{
        problem(post(alice,"/api/workspaces/1/projects",HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}"),"Origin","http://evil.test"),403,"DEV_ACCESS_DENIED");
        problem(post(alice,"/api/workspaces/1/projects",HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")),403,"DEV_ACCESS_DENIED");
        problem(post(alice,"/api/workspaces/2/projects",HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}"),"Origin","http://127.0.0.1:5173","X-User-Id","5","X-Role","OWNER"),404,"PROJECT_NOT_FOUND");
    }
    @Test void parallelDuplicatesGetIndependentIds()throws Exception{
        try(var pool=Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->create("{\"name\":\"Same\"}"));var two=pool.submit(()->create("{\"name\":\"Same\"}"));
            var a=one.get();var b=two.get();assertThat(a.statusCode()).isEqualTo(201);assertThat(b.statusCode()).isEqualTo(201);
            assertThat(body(a).path("id")).isNotEqualTo(body(b).path("id"));
        }
    }
    @Test void actorDisappearanceAndUnexpectedDatabaseErrorAreSanitized()throws Exception{
        db.sql("UPDATE flowdesk.users SET email='temporarily-away@example.test' WHERE email='alice@example.test'");
        try{problem(call(alice,"/api/workspaces/1/projects"),503,"SERVICE_UNAVAILABLE");}
        finally{db.sql("UPDATE flowdesk.users SET email='alice@example.test' WHERE email='temporarily-away@example.test'");}
        db.sql("REVOKE SELECT ON flowdesk.projects FROM flowdesk_app");
        try{problem(call(alice,"/api/workspaces/1/projects"),500,"INTERNAL_ERROR");}
        finally{db.sql("GRANT SELECT ON flowdesk.projects TO flowdesk_app");}
    }
    @ParameterizedTest @ValueSource(strings={"text/plain","application/json;charset=UTF-16","application/x-www-form-urlencoded"})
    void nestedPostRequiresUtf8Json(String contentType)throws Exception{
        int port=((ServletWebServerApplicationContext)alice).getWebServer().getPort();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/workspaces/1/projects"))
                .version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(8)).header("Origin","http://127.0.0.1:5173")
                .header("Content-Type",contentType).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build();
        problem(HTTP.send(request,HttpResponse.BodyHandlers.ofString()),415,"UNSUPPORTED_MEDIA_TYPE");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void persistAndCommitFailuresNeverReturn201OrLeaveRows(boolean deferred)throws Exception{
        db.sql("CREATE FUNCTION flowdesk.reject_project() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'isolated project failure'; END$$");
        db.sql(deferred?"CREATE CONSTRAINT TRIGGER reject_project AFTER INSERT ON flowdesk.projects DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_project()":"CREATE TRIGGER reject_project BEFORE INSERT ON flowdesk.projects FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_project()");
        problem(create("{\"name\":\"Rollback\"}"),500,"INTERNAL_ERROR");assertThat(db.query("SELECT count(*) FROM flowdesk.projects")).isEqualTo("0");
    }
    @Test void disabledAndDefaultModesAndMissingPrivilegesFailClosed()throws Exception{
        try(var read=startFor(db,"dev-actor",Map.of("FLOWDESK_DEV_ACTOR_EMAIL","alice@example.test"))){
            assertThat(read.getBeansOfType(ProjectCommandService.class)).isEmpty();assertThat(call(read,"/api/workspaces/1/projects").statusCode()).isEqualTo(200);
            assertThat(create(read,"/api/workspaces/1/projects","{\"name\":\"x\"}").statusCode()).isEqualTo(405);
        }
        try(var off=startFor(db,"default",Map.of())){assertThat(call(off,"/api/workspaces/1/projects").statusCode()).isEqualTo(404);}
        db.sql("REVOKE SELECT ON flowdesk.projects FROM flowdesk_app");
        try{assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",Map.of("FLOWDESK_DEV_ACTOR_EMAIL","alice@example.test"))) {}}).hasStackTraceContaining("Project SELECT access");}
        finally{db.sql("GRANT SELECT ON flowdesk.projects TO flowdesk_app");}
        db.sql("REVOKE INSERT ON flowdesk.projects FROM flowdesk_app");
        try{assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",options("alice"))) {}}).hasStackTraceContaining("Development workspace writes require approved minimum privileges");}
        finally{db.sql("GRANT INSERT ON flowdesk.projects TO flowdesk_app");}
    }
}
