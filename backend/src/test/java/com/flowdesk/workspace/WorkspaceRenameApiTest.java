package com.flowdesk.workspace;

import com.flowdesk.support.IsolatedReadDatabase;
import com.flowdesk.workspace.application.WorkspaceCommandService;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static com.flowdesk.workspace.WorkspaceReadApiTest.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class WorkspaceRenameApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("rename_api").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static String id, originals;
    static Map<String,Object> options(String user) {
        return Map.of("FLOWDESK_DEV_ACTOR_EMAIL",user+"@example.test", "flowdesk.dev.workspace-write-enabled","true",
                "FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS","http://127.0.0.1:5173");
    }
    @BeforeAll static void prepare() throws Exception {
        db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();db.grantTaskStatusHistoryRead().success();db.grantTaskAssigneeDueDate().success();
        id=db.query("SELECT id FROM flowdesk.workspaces WHERE name='FlowDesk Demo A'");
        originals=db.snapshot();alice=startFor(db,"dev-actor",options("alice"));
    }
    @AfterEach void restore() throws Exception {
        db.sql("DROP TRIGGER IF EXISTS reject_rename ON flowdesk.workspaces; DROP TRIGGER IF EXISTS reject_rename_commit ON flowdesk.workspaces; DROP FUNCTION IF EXISTS flowdesk.reject_rename()");
        db.sql("UPDATE flowdesk.workspaces SET name='FlowDesk Demo A',version=0,updated_at=created_at WHERE id="+id);
        assertThat(db.snapshot()).isEqualTo(originals);
    }
    @AfterAll static void close() { if(alice!=null)alice.close(); }
    static HttpResponse<String> patch(ConfigurableApplicationContext context,String path,String json,String... headers) throws Exception {
        int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12))
                .header("Accept","application/json").method("PATCH",HttpRequest.BodyPublishers.ofString(json));
        for(int i=0;i<headers.length;i+=2)req.header(headers[i],headers[i+1]);
        return HTTP.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> rename(ConfigurableApplicationContext context,String name,String version) throws Exception {
        return patch(context,"/api/workspaces/"+id,JSON.writer().with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature())
                .writeValueAsString(Map.of("name",name,"version",version)),
                "Origin","http://127.0.0.1:5173","Content-Type","application/json");
    }
    @Test void ownerAndAdminNormalizeChangeVersionAndReadSameDto() throws Exception {
        for(String user:new String[]{"alice","bob"}) {
            try(var context=startFor(db,"dev-actor",options(user))) {
                String version=user.equals("alice")?"0":"1";
                var response=rename(context,"  Renamed "+user+"  ",version);
                assertThat(response.statusCode()).isEqualTo(200);
                var dto=body(response);assertThat(dto.size()).isEqualTo(4);
                assertThat(dto.path("version").isTextual()).isTrue();
                assertThat(dto.path("version").asText()).isEqualTo(user.equals("alice")?"1":"2");
                assertThat(dto.path("name").asText()).isEqualTo("Renamed "+user);
                assertThat(dto.path("myRole").asText()).isEqualTo(user.equals("alice")?"OWNER":"ADMIN");
                assertThat(body(call(context,"/api/workspaces/"+id))).isEqualTo(dto);
                assertThat(body(call(context,"/api/workspaces")).path("items").toString()).contains("Renamed "+user);
            }
        }
        assertThat(db.query("SELECT updated_at>created_at FROM flowdesk.workspaces WHERE id="+id)).isEqualTo("t");
    }
    @Test void memberViewerAndUnjoinedOwnerRespectErrorPrecedence() throws Exception {
        for(String user:new String[]{"carol","dave","eve"}) {
            try(var context=startFor(db,"dev-actor",options(user))) {
                problem(rename(context,"Denied","99"),user.equals("eve")?404:403,
                        user.equals("eve")?"WORKSPACE_NOT_FOUND":"WORKSPACE_FORBIDDEN");
                problem(patch(context,"/api/workspaces/9223372036854775807","{\"name\":\"x\",\"version\":\"99\"}",
                        "Origin","http://127.0.0.1:5173","Content-Type","application/json"),404,"WORKSPACE_NOT_FOUND");
            }
        }
    }
    @Test void staleVersionFailsAndLatestExplicitRequestSucceeds() throws Exception {
        assertThat(rename(alice,"First","0").statusCode()).isEqualTo(200);
        problem(rename(alice,"Lost change","0"),409,"WORKSPACE_VERSION_CONFLICT");
        assertThat(body(call(alice,"/api/workspaces/"+id)).path("name").asText()).isEqualTo("First");
        assertThat(rename(alice,"Second","1").statusCode()).isEqualTo(200);
    }
    @Test void normalizedNoOpPreservesVersionTimestampButStaleNoOpFails() throws Exception {
        assertThat(rename(alice,"  FlowDesk Demo A  ","0").statusCode()).isEqualTo(200);
        assertThat(db.snapshot()).isEqualTo(originals);
        problem(rename(alice,"FlowDesk Demo A","1"),409,"WORKSPACE_VERSION_CONFLICT");
    }
    @Test void noOpDetectsAnInterveningCommitAtOuterTransactionCompletion() throws Exception {
        var tx=new TransactionTemplate(alice.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(()->tx.execute(status->{
            var view=alice.getBean(WorkspaceCommandService.class).rename(Long.parseLong(id),"FlowDesk Demo A",0);
            assertThat(view.version()).isEqualTo("0");
            try { assertThat(rename(alice,"Concurrent committed","0").statusCode()).isEqualTo(200); }
            catch(Exception e) { throw new RuntimeException(e); }
            return null;
        })).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(body(call(alice,"/api/workspaces/"+id)).path("name").asText()).isEqualTo("Concurrent committed");
    }
    @Test void twoRequestsThatReadSameVersionOnlyCommitOnce() throws Exception {
        try(var blocker=db.admin();var executor=Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);blocker.createStatement().execute("SELECT id FROM flowdesk.workspaces WHERE id="+id+" FOR UPDATE");
            var first=executor.submit(()->rename(alice,"Race one","0"));
            var second=executor.submit(()->rename(alice,"Race two","0"));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
                while(System.nanoTime()<deadline && !db.query("SELECT count(*)>=2 FROM pg_stat_activity WHERE datname=current_database() AND usename='flowdesk_app' AND wait_event_type='Lock' AND lower(query) LIKE '%update%workspaces%'").equals("t")) Thread.sleep(25);
                assertThat(db.query("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename='flowdesk_app' AND wait_event_type='Lock' AND lower(query) LIKE '%update%workspaces%'"))
                        .as("both real transactions have read version 0 and reached UPDATE").isEqualTo("2");
            } finally { blocker.rollback(); }
            var one=first.get(12,TimeUnit.SECONDS);var two=second.get(12,TimeUnit.SECONDS);
            assertThat(List.of(one.statusCode(),two.statusCode())).containsExactlyInAnyOrder(200,409);
            problem(one.statusCode()==409?one:two,409,"WORKSPACE_VERSION_CONFLICT");
            assertThat(db.query("SELECT version FROM flowdesk.workspaces WHERE id="+id)).isEqualTo("1");
            assertThat(body(call(alice,"/api/workspaces/"+id)).path("name").asText())
                    .isEqualTo(body(one.statusCode()==200?one:two).path("name").asText());
        }
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"name\":\"x\"}","{\"name\":\"x\",\"version\":0}",
        "{\"name\":\"x\",\"version\":null}","{\"name\":null,\"version\":\"0\"}",
        "{\"name\":\"x\",\"version\":\"0\",\"role\":\"OWNER\"}",
        "{\"name\":\"x\",\"version\":\"0\",\"version\":\"0\"}","{\"name\":\"x\",\"version\":\"0\"}{}"})
    void strictJsonRejectsMassAssignmentAndMalformedBody(String json) throws Exception {
        problem(patch(alice,"/api/workspaces/"+id,json,"Origin","http://127.0.0.1:5173","Content-Type","application/json"),400,"INVALID_REQUEST");
    }
    @ParameterizedTest @ValueSource(strings={"-1","00","01","+1","1.0"," 0","9223372036854775808",""})
    void invalidVersionStringsAre400(String version) throws Exception { problem(rename(alice,"x",version),400,"INVALID_REQUEST"); }
    @ParameterizedTest @ValueSource(strings={" ","\u0000","\u0001","\u007f","\ud800"})
    void invalidNamesAre400(String name) throws Exception { problem(rename(alice,name,"0"),400,"INVALID_REQUEST"); }
    @Test void unicodeLengthBoundedBodyAndQueryAreValidated() throws Exception {
        assertThat(rename(alice,"😀".repeat(100),"0").statusCode()).isEqualTo(200);
        problem(rename(alice,"😀".repeat(101),"1"),400,"INVALID_REQUEST");
        problem(patch(alice,"/api/workspaces/"+id,"{\"name\":\""+"a".repeat(4100)+"\",\"version\":\"1\"}",
                "Origin","http://127.0.0.1:5173","Content-Type","application/json"),413,"REQUEST_TOO_LARGE");
        problem(patch(alice,"/api/workspaces/"+id+"?userId=1","{\"name\":\"x\",\"version\":\"1\"}",
                "Origin","http://127.0.0.1:5173","Content-Type","application/json"),400,"INVALID_REQUEST");
    }
    @Test void originsMediaAndWriteModesAreEnforced() throws Exception {
        for(String[] headers:new String[][]{
            {"Content-Type","application/json"},
            {"Origin","http://evil.test","Content-Type","application/json"},
            {"Origin","http://127.0.0.1:5173","Sec-Fetch-Site","cross-site","Content-Type","application/json"}}) {
            problem(patch(alice,"/api/workspaces/"+id,"{\"name\":\"x\",\"version\":\"0\"}",headers),403,"DEV_ACCESS_DENIED");
        }
        problem(patch(alice,"/api/workspaces/"+id,"{}","Origin","http://127.0.0.1:5173","Content-Type","text/plain"),415,"UNSUPPORTED_MEDIA_TYPE");
        for(String profile:new String[]{"default","dev-actor"}) {
            try(var context=startFor(db,profile,Map.of("FLOWDESK_DEV_ACTOR_EMAIL","alice@example.test"))) {
                assertThat(patch(context,"/api/workspaces/"+id,"{}").statusCode()).isEqualTo(profile.equals("default")?404:405);
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void updateAndCommitFailureRollBackAllWorkspaceChanges(boolean deferred) throws Exception {
        db.sql("CREATE FUNCTION flowdesk.reject_rename() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'isolated failure'; END$$");
        db.sql(deferred?"CREATE CONSTRAINT TRIGGER reject_rename_commit AFTER UPDATE ON flowdesk.workspaces DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_rename()"
                :"CREATE TRIGGER reject_rename BEFORE UPDATE ON flowdesk.workspaces FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_rename()");
        problem(rename(alice,"Must rollback","0"),500,"INTERNAL_ERROR");assertThat(db.snapshot()).isEqualTo(originals);
    }
    @Test void createOnlyPrivilegesCannotActivateRenameMode() throws Exception {
        db.sql("REVOKE UPDATE(name,updated_at,version) ON flowdesk.workspaces FROM flowdesk_app");
        try {
            assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",options("alice"))) {}})
                    .hasStackTraceContaining("Development workspace writes require approved minimum privileges");
        } finally { db.sql("GRANT UPDATE(name,updated_at,version) ON flowdesk.workspaces TO flowdesk_app");db.grantRename().success(); }
    }
}
