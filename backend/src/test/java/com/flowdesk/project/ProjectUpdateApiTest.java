package com.flowdesk.project;

import com.flowdesk.project.application.ProjectCommandService;
import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.flowdesk.workspace.WorkspaceReadApiTest.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class ProjectUpdateApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("project_update").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static String parents, original;
    static Map<String,Object> options(String user) {
        return Map.of("FLOWDESK_DEV_ACTOR_EMAIL",user+"@example.test","flowdesk.dev.workspace-write-enabled","true",
                "FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS","http://127.0.0.1:5173","spring.datasource.hikari.data-source-properties.socketTimeout","5");
    }
    @BeforeAll static void prepare() throws Exception {
        db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();db.grantTaskStatusHistoryRead().success();db.grantTaskAssigneeDueDate().success();
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name,description) VALUES(1,'Initial','Original'),(2,'Foreign',NULL)");
        parents=db.snapshot();original=row();alice=startFor(db,"dev-actor",options("alice"));
    }
    @AfterAll static void close() { if(alice!=null)alice.close(); }
    @AfterEach void restore() throws Exception {
        db.sql("DROP TRIGGER IF EXISTS reject_project_update ON flowdesk.projects; DROP FUNCTION IF EXISTS flowdesk.reject_project_update(); UPDATE flowdesk.projects SET name='Initial',description='Original',version=0,updated_at=created_at WHERE id=1; DELETE FROM flowdesk.workspace_memberships WHERE workspace_id=2 AND user_id=(SELECT id FROM flowdesk.users WHERE email='alice@example.test')");
        assertThat(db.snapshot()).isEqualTo(parents);assertThat(row()).isEqualTo(original);
    }
    static String row() throws Exception { return db.query("SELECT row_to_json(p)::text FROM flowdesk.projects p WHERE id=1"); }
    static HttpResponse<String> patch(ConfigurableApplicationContext ctx,String path,HttpRequest.BodyPublisher payload,String...headers) throws Exception {
        int port=((ServletWebServerApplicationContext)ctx).getWebServer().getPort();
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(12)).header("Accept","application/json").method("PATCH",payload);
        for(int i=0;i<headers.length;i+=2)req.header(headers[i],headers[i+1]);
        return HTTP.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> raw(ConfigurableApplicationContext ctx,String path,String payload) throws Exception {
        return patch(ctx,path,HttpRequest.BodyPublishers.ofString(payload),"Origin","http://127.0.0.1:5173","Content-Type","application/json");
    }
    static HttpResponse<String> update(ConfigurableApplicationContext ctx,String name,String description,String version) throws Exception {
        var payload=new HashMap<String,Object>();payload.put("name",name);payload.put("description",description);payload.put("version",version);
        return raw(ctx,"/api/workspaces/1/projects/1",JSON.writer().with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature()).writeValueAsString(payload));
    }
    @Test void ownerAndAdminUpdateBothFieldsAndReadVersionWithoutChangingParent() throws Exception {
        for(String user:new String[]{"alice","bob"}) {
            try(var ctx=startFor(db,"dev-actor",options(user))) {
                String version=user.equals("alice")?"0":"1";
                var result=update(ctx,"  Updated "+user+"  ","  first\r\nsecond\tline  ",version);
                assertThat(result.statusCode()).isEqualTo(200);var dto=body(result);
                assertThat(dto.size()).isEqualTo(7);assertThat(dto.path("version").isTextual()).isTrue();
                assertThat(dto.path("version").asText()).isEqualTo(user.equals("alice")?"1":"2");
                assertThat(dto.path("name").asText()).isEqualTo("Updated "+user);
                assertThat(dto.path("description").asText()).isEqualTo("first\nsecond\tline");
                assertThat(body(call(ctx,"/api/workspaces/1/projects/1"))).isEqualTo(dto);
                var item=body(call(ctx,"/api/workspaces/1/projects")).path("items").get(0);
                assertThat(item.size()).isEqualTo(4);assertThat(item.path("name")).isEqualTo(dto.path("name"));
                assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
            }
        }
        assertThat(db.snapshot()).isEqualTo(parents);
        assertThat(db.query("SELECT updated_at>created_at FROM flowdesk.projects WHERE id=1")).isEqualTo("t");
    }
    @Test void individualFieldsAndDescriptionClearNoOpAndStaleCheck() throws Exception {
        assertThat(update(alice,"Initial","Description only","0").statusCode()).isEqualTo(200);
        assertThat(update(alice,"Name only","Description only","1").statusCode()).isEqualTo(200);
        var clear=update(alice,"Name only"," \r\n\u00a0 ","2");assertThat(clear.statusCode()).isEqualTo(200);
        assertThat(body(clear).path("description").isNull()).isTrue();assertThat(body(clear).path("version").asText()).isEqualTo("3");
        String before=row();assertThat(update(alice," Name only ",null,"3").statusCode()).isEqualTo(200);assertThat(row()).isEqualTo(before);
        problem(update(alice,"Name only",null,"2"),409,"PROJECT_VERSION_CONFLICT");assertThat(row()).isEqualTo(before);
    }
    @Test void normalizedNoOpPreservesTimeAndVersion() throws Exception {
        var response=update(alice," \u00a0Initial "," Original ","0");
        assertThat(response.statusCode()).isEqualTo(200);assertThat(body(response).path("version").asText()).isEqualTo("0");
        assertThat(row()).isEqualTo(original);problem(update(alice,"Initial","Original","1"),409,"PROJECT_VERSION_CONFLICT");
    }
    @ParameterizedTest @ValueSource(strings={"carol","dave","eve"})
    void scopeRoleAndVersionErrorPrecedence(String user) throws Exception {
        try(var ctx=startFor(db,"dev-actor",options(user))) {
            problem(update(ctx,"Denied","Denied","99"),user.equals("eve")?404:403,user.equals("eve")?"PROJECT_NOT_FOUND":"PROJECT_FORBIDDEN");
            for(String path:List.of("/api/workspaces/1/projects/2","/api/workspaces/1/projects/9223372036854775807","/api/workspaces/9223372036854775807/projects/1"))
                problem(raw(ctx,path,"{\"name\":\"x\",\"description\":null,\"version\":\"99\"}"),404,"PROJECT_NOT_FOUND");
        }
    }
    @Test void twoMembershipsStillCannotChangeUrlParent() throws Exception {
        db.sql("INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) SELECT 2,id,'VIEWER' FROM flowdesk.users WHERE email='alice@example.test'");
        assertThat(call(alice,"/api/workspaces/2/projects/2").statusCode()).isEqualTo(200);
        problem(raw(alice,"/api/workspaces/2/projects/1","{\"name\":\"x\",\"description\":null,\"version\":\"99\"}"),404,"PROJECT_NOT_FOUND");
        problem(raw(alice,"/api/workspaces/2/projects/2","{\"name\":\"x\",\"description\":null,\"version\":\"99\"}"),403,"PROJECT_FORBIDDEN");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualParallelTransactionsOnlyCommitOneEvenForDifferentFields(boolean differentFields) throws Exception {
        try(var blocker=db.admin();var pool=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);blocker.createStatement().execute("SELECT id FROM flowdesk.projects WHERE id=1 FOR UPDATE");
            var a=pool.submit(()->update(alice,"Race A","Original","0"));
            var b=pool.submit(()->update(alice,differentFields?"Initial":"Race B",differentFields?"Description B":"Original","0"));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                while(System.nanoTime()<deadline && !db.query("SELECT count(*)>=2 FROM pg_stat_activity WHERE datname=current_database() AND usename='flowdesk_app' AND wait_event_type='Lock' AND lower(query) LIKE '%update%projects%'").equals("t")) Thread.sleep(20);
                assertThat(db.query("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename='flowdesk_app' AND wait_event_type='Lock' AND lower(query) LIKE '%update%projects%'"))
                        .as("both independent transactions read version 0 before UPDATE").isEqualTo("2");
            } finally {blocker.rollback();}
            var one=a.get(12,TimeUnit.SECONDS);var two=b.get(12,TimeUnit.SECONDS);
            assertThat(List.of(one.statusCode(),two.statusCode())).containsExactlyInAnyOrder(200,409);
            problem(one.statusCode()==409?one:two,409,"PROJECT_VERSION_CONFLICT");
            assertThat(body(call(alice,"/api/workspaces/1/projects/1"))).isEqualTo(body(one.statusCode()==200?one:two));
            assertThat(db.query("SELECT version FROM flowdesk.projects WHERE id=1")).isEqualTo("1");
        }
    }
    @Test void noOpValidatesAnInterveningCommitAtTransactionCompletion() {
        var tx=new TransactionTemplate(alice.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(()->tx.execute(status->{
            var dto=alice.getBean(ProjectCommandService.class).update(1,1,"Initial","Original",0);assertThat(dto.version()).isEqualTo("0");
            try {assertThat(update(alice,"Concurrent","Original","0").statusCode()).isEqualTo(200);}
            catch(Exception failure){throw new RuntimeException(failure);}
            return null;
        })).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"name\":\"x\",\"version\":\"0\"}",
            "{\"name\":\"x\",\"description\":null,\"version\":0}","{\"name\":null,\"description\":null,\"version\":\"0\"}",
            "{\"name\":\"x\",\"description\":42,\"version\":\"0\"}","{\"name\":\"x\",\"description\":null,\"version\":\"0\",\"workspaceId\":2}",
            "{\"name\":\"x\",\"description\":null,\"version\":\"0\",\"version\":\"0\"}","{\"name\":\"x\",\"description\":null,\"version\":\"0\"} {}"})
    void strictJsonAndRequiredDescription(String payload) throws Exception {problem(raw(alice,"/api/workspaces/1/projects/1",payload),400,"INVALID_REQUEST");}
    @ParameterizedTest @ValueSource(strings={"","-1","00","01","+1","1.0","9223372036854775808"})
    void invalidVersion(String version) throws Exception {problem(update(alice,"x",null,version),400,"INVALID_REQUEST");}
    @Test void unicodeValidationAndActualChunkedBodyLimit() throws Exception {
        problem(update(alice,"🚀".repeat(101),null,"0"),400,"INVALID_REQUEST");
        problem(update(alice,"x","🚀".repeat(2001),"0"),400,"INVALID_REQUEST");
        problem(update(alice,"\uD800",null,"0"),400,"INVALID_REQUEST");
        problem(update(alice,"x","a\u0000b","0"),400,"INVALID_REQUEST");
        assertThat(update(alice,"🚀".repeat(100),"🚀".repeat(2000),"0").statusCode()).isEqualTo(200);
        var body=HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(" ".repeat(32769)));
        problem(patch(alice,"/api/workspaces/1/projects/1",body,"Origin","http://127.0.0.1:5173","Content-Type","application/json"),413,"REQUEST_TOO_LARGE");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void updateAndDeferredCommitFailuresRollbackAllFields(boolean deferred) throws Exception {
        db.sql("CREATE FUNCTION flowdesk.reject_project_update() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'isolated project failure'; END$$");
        db.sql(deferred?"CREATE CONSTRAINT TRIGGER reject_project_update AFTER UPDATE ON flowdesk.projects DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_project_update()":"CREATE TRIGGER reject_project_update BEFORE UPDATE ON flowdesk.projects FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_project_update()");
        problem(update(alice,"Rollback","Rollback","0"),500,"INTERNAL_ERROR");assertThat(row()).isEqualTo(original);
    }
    @Test void transportModesAndMissingUpdatePrivilegeFailClosed() throws Exception {
        String valid="{\"name\":\"x\",\"description\":null,\"version\":\"0\"}";
        problem(patch(alice,"/api/workspaces/1/projects/1",HttpRequest.BodyPublishers.ofString(valid),"Origin","http://evil.test","Content-Type","application/json"),403,"DEV_ACCESS_DENIED");
        problem(patch(alice,"/api/workspaces/1/projects/1",HttpRequest.BodyPublishers.ofString(valid),"Origin","http://127.0.0.1:5173","Content-Type","text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");
        problem(raw(alice,"/api/workspaces/01/projects/1",valid),400,"INVALID_REQUEST");
        problem(raw(alice,"/api/workspaces/1/projects/1?role=OWNER",valid),400,"INVALID_REQUEST");
        for(String profile:List.of("default","dev-actor")) try(var ctx=startFor(db,profile,Map.of("FLOWDESK_DEV_ACTOR_EMAIL","alice@example.test"))) {
            assertThat(raw(ctx,"/api/workspaces/1/projects/1",valid).statusCode()).isEqualTo(profile.equals("default")?404:405);
        }
        db.sql("REVOKE UPDATE(name,description,updated_at,version) ON flowdesk.projects FROM flowdesk_app");
        try {assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",options("alice"))) {}}).hasStackTraceContaining("Development workspace writes require approved minimum privileges");}
        finally {db.sql("GRANT UPDATE(name,description,updated_at,version) ON flowdesk.projects TO flowdesk_app");}
    }
}
