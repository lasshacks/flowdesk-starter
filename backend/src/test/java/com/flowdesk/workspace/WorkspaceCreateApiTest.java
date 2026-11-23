package com.flowdesk.workspace;

import com.flowdesk.support.IsolatedReadDatabase;
import com.flowdesk.workspace.application.WorkspaceCommandService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static com.flowdesk.workspace.WorkspaceReadApiTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class WorkspaceCreateApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("create_api").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase database;
    static ConfigurableApplicationContext dave;
    static String originals, initialIds;

    static Map<String,Object> options() {
        return Map.of("FLOWDESK_DEV_ACTOR_EMAIL", "dave@example.test", "flowdesk.dev.workspace-write-enabled", "true",
                "FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS", "http://127.0.0.1:5173");
    }
    @BeforeAll static void prepare() throws Exception {
        database = new IsolatedReadDatabase(POSTGRES, true);
        originals = database.snapshot();
        initialIds = database.query("SELECT string_agg(id::text,',' ORDER BY id) FROM flowdesk.workspaces");
        assertThat(database.grantCreate().success()).contains("WORKSPACE_CREATE");database.grantRename().success();database.grantProject().success();database.grantProjectUpdate().success();database.grantTask().success();database.grantTaskUpdate().success();database.grantTaskStatusChange().success();database.grantTaskStatusHistoryRead().success();database.grantTaskAssigneeDueDate().success();
        dave = startFor(database, "dev-actor", options());
    }
    @AfterEach void restoreData() throws Exception {
        database.sql("DROP TRIGGER IF EXISTS reject_created_owner ON flowdesk.workspace_memberships");
        database.sql("DROP TRIGGER IF EXISTS reject_deferred_owner ON flowdesk.workspace_memberships");
        database.sql("DROP FUNCTION IF EXISTS flowdesk.reject_created_owner()");
        database.sql("DELETE FROM flowdesk.workspace_memberships WHERE workspace_id NOT IN ("+initialIds+")");
        database.sql("DELETE FROM flowdesk.workspaces WHERE id NOT IN ("+initialIds+")");
        database.sql("DELETE FROM flowdesk.users WHERE email='unjoined@example.test'");
        assertThat(database.snapshot()).isEqualTo(originals);
    }
    @AfterAll static void finish() { if(dave!=null)dave.close(); }

    static HttpResponse<String> post(ConfigurableApplicationContext context, String payload, String... headers) throws Exception {
        return postBody(context, "/api/workspaces", HttpRequest.BodyPublishers.ofString(payload), headers);
    }
    static HttpResponse<String> postBody(ConfigurableApplicationContext context, String path,
            HttpRequest.BodyPublisher payload, String... headers) throws Exception {
        int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(8))
                .header("Accept","application/json").POST(payload);
        for(int i=0;i<headers.length;i+=2)request.header(headers[i],headers[i+1]);
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> create(String name) throws Exception {
        return post(dave,JSON.writer().with(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature())
                .writeValueAsString(Map.of("name",name)),"Origin","http://127.0.0.1:5173","Content-Type","application/json");
    }
    static void owner(String id, String email) throws Exception {
        assertThat(database.query("SELECT count(*) FROM flowdesk.workspace_memberships m JOIN flowdesk.users u ON u.id=m.user_id WHERE m.workspace_id="+id+" AND m.role='OWNER' AND u.email='"+email+"'")).isEqualTo("1");
        assertThat(database.query("SELECT count(*) FROM flowdesk.workspace_memberships WHERE workspace_id="+id)).isEqualTo("1");
    }

    @Test void viewerCreatesOwnSpaceAndOtherUserCannotReadIt() throws Exception {
        var response=create(" \u00a0 새 팀  공간 \u2003 ");
        assertThat(response.statusCode()).isEqualTo(201);
        var dto=body(response); String id=dto.path("id").asText();
        assertThat(dto.size()).isEqualTo(4);
        assertThat(dto.path("version").asText()).isEqualTo("0");
        assertThat(dto.path("id").isTextual()).isTrue();
        assertThat(dto.path("name").asText()).isEqualTo("새 팀  공간");
        assertThat(dto.path("myRole").asText()).isEqualTo("OWNER");
        assertThat(response.headers().firstValue("Location")).contains("/api/workspaces/"+id);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        owner(id,"dave@example.test");
        assertThat(body(call(dave,"/api/workspaces/"+id))).isEqualTo(dto);
        assertThat(body(call(dave,"/api/workspaces")).path("items").toString()).contains("새 팀  공간");
        try(var eve=startFor(database,"dev-actor",Map.of("FLOWDESK_DEV_ACTOR_EMAIL","eve@example.test"))) {
            problem(call(eve,"/api/workspaces/"+id),404,"WORKSPACE_NOT_FOUND");
        }
        assertThat(database.query("SELECT role FROM flowdesk.workspace_memberships m JOIN flowdesk.users u ON u.id=m.user_id JOIN flowdesk.workspaces w ON w.id=m.workspace_id WHERE u.email='dave@example.test' AND w.name='FlowDesk Demo A'")).isEqualTo("VIEWER");
    }

    @Test void unjoinedUserCanCreateFirstWorkspace() throws Exception {
        database.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('unjoined@example.test','Unjoined')");
        var properties=new HashMap<>(options());properties.put("FLOWDESK_DEV_ACTOR_EMAIL","unjoined@example.test");
        try(var context=startFor(database,"dev-actor",properties)) {
            var response=post(context,"{\"name\":\"First\"}","Origin","http://127.0.0.1:5173","Content-Type","application/json");
            assertThat(response.statusCode()).isEqualTo(201);owner(body(response).path("id").asText(),"unjoined@example.test");
        }
    }

    @ParameterizedTest @ValueSource(strings={"", " ", "\u00a0\u2003", "a\u0000b", "a\nb", "\uD800"})
    void invalidNamesFailWithoutRows(String name) throws Exception {
        problem(create(name),400,"INVALID_REQUEST"); assertThat(database.snapshot()).isEqualTo(originals);
    }

    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"name\":null}","{\"name\":42}",
            "{\"name\":\"x\",\"role\":\"OWNER\"}","{\"name\":\"x\",\"ownerId\":1}","{\"name\":\"x\",\"id\":99}",
            "{\"name\":\"x\",\"name\":\"y\"}","{\"name\":\"x\"} {}","{",""})
    void invalidJsonAndMassAssignmentAreRejected(String payload) throws Exception {
        problem(post(dave,payload,"Origin","http://127.0.0.1:5173","Content-Type","application/json"),400,"INVALID_REQUEST");
        assertThat(database.snapshot()).isEqualTo(originals);
    }

    @Test void unicodeLengthAndChunkedBodyLimitAreEnforced() throws Exception {
        assertThat(create("🚀".repeat(100)).statusCode()).isEqualTo(201);
        problem(create("🚀".repeat(101)),400,"INVALID_REQUEST");
        String huge="{\"name\":\"x\"}"+" ".repeat(4096);
        var publisher=HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(huge));
        problem(postBody(dave,"/api/workspaces",publisher,"Origin","http://127.0.0.1:5173","Content-Type","application/json"),413,"REQUEST_TOO_LARGE");
        problem(postBody(dave,"/api/workspaces?name=x",HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}"),"Origin","http://127.0.0.1:5173","Content-Type","application/json"),400,"INVALID_REQUEST");
        problem(postBody(dave,"/api/workspaces",HttpRequest.BodyPublishers.ofByteArray("{\"name\":\"UTF16\"}".getBytes(java.nio.charset.StandardCharsets.UTF_16LE)),
                "Origin","http://127.0.0.1:5173","Content-Type","application/json"),400,"INVALID_REQUEST");
        problem(postBody(dave,"/api/workspaces",HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte)0xC3,0x28}),
                "Origin","http://127.0.0.1:5173","Content-Type","application/json"),400,"INVALID_REQUEST");
    }

    @ParameterizedTest @ValueSource(strings={"null","http://evil.test:5173","http://127.0.0.1.evil.test:5173","http://127.0.0.1:5174",
            "http://localhost:5173","http://127.0.0.1:5173/","http://127.0.0.1:5173,http://evil.test"})
    void untrustedOriginCannotWrite(String origin) throws Exception {
        problem(post(dave,"{\"name\":\"x\"}","Origin",origin,"Content-Type","application/json"),403,"DEV_ACCESS_DENIED");
        assertThat(database.snapshot()).isEqualTo(originals);
    }

    @Test void absentDuplicateAndCrossSiteOriginAreBlocked() throws Exception {
        for(var headers:new String[][]{
                {"Content-Type","application/json"},
                {"Origin","http://127.0.0.1:5173","Origin","http://127.0.0.1:5173","Content-Type","application/json"},
                {"Origin","http://127.0.0.1:5173","Sec-Fetch-Site","cross-site","Content-Type","application/json"},
                {"Referer","http://127.0.0.1:5173/","X-Forwarded-Host","127.0.0.1:5173","Content-Type","application/json"}}) {
            problem(post(dave,"{\"name\":\"x\"}",headers),403,"DEV_ACCESS_DENIED");
        }
        assertThat(database.snapshot()).isEqualTo(originals);
    }

    @ParameterizedTest @ValueSource(strings={"text/plain","application/x-www-form-urlencoded","application/json;charset=UTF-16","broken"})
    void onlyUtf8JsonCanWrite(String type) throws Exception {
        problem(post(dave,"{\"name\":\"x\"}","Origin","http://127.0.0.1:5173","Content-Type",type),415,"UNSUPPORTED_MEDIA_TYPE");
    }

    @Test void independentConcurrentDuplicateRequestsProduceTwoOwnedSpaces() throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->create("Same"));var second=executor.submit(()->create("Same"));
            var one=first.get();var two=second.get();
            assertThat(one.statusCode()).isEqualTo(201);assertThat(two.statusCode()).isEqualTo(201);
            String id1=body(one).path("id").asText(),id2=body(two).path("id").asText();
            assertThat(id1).isNotEqualTo(id2);owner(id1,"dave@example.test");owner(id2,"dave@example.test");
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void membershipInsertOrDeferredCommitFailureRollsBackWorkspace(boolean deferred) throws Exception {
        database.sql("CREATE FUNCTION flowdesk.reject_created_owner() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'isolated owner failure'; END$$");
        database.sql(deferred ? "CREATE CONSTRAINT TRIGGER reject_deferred_owner AFTER INSERT ON flowdesk.workspace_memberships DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_created_owner()"
                : "CREATE TRIGGER reject_created_owner BEFORE INSERT ON flowdesk.workspace_memberships FOR EACH ROW EXECUTE FUNCTION flowdesk.reject_created_owner()");
        try {
            problem(create("Rollback"),500,"INTERNAL_ERROR");
            assertThat(database.snapshot()).isEqualTo(originals);
        } finally {
            database.sql("DROP TRIGGER IF EXISTS reject_created_owner ON flowdesk.workspace_memberships");
            database.sql("DROP TRIGGER IF EXISTS reject_deferred_owner ON flowdesk.workspace_memberships");
        }
        assertThat(create("Rollback").statusCode()).isEqualTo(201);
    }

    @Test void defaultAndWriteDisabledModesDoNotExposePost() throws Exception {
        for(String profile:new String[]{"default","dev-actor"}) {
            try(var context=startFor(database,profile,Map.of("FLOWDESK_DEV_ACTOR_EMAIL","dave@example.test"))) {
                assertThat(context.getBeansOfType(WorkspaceCommandService.class)).isEmpty();
                assertThat(post(context,"{\"name\":\"x\"}").statusCode()).isEqualTo(profile.equals("default")?404:405);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={"","null","http://evil.test:5173","http://127.0.0.1:0","http://127.0.0.1:65536","http://127.0.0.1:5173/"})
    void invalidOriginConfigurationFailsStartup(String origins) {
        var properties=new HashMap<>(options());properties.put("FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS",origins);
        assertThatThrownBy(()->{try(var ignored=startFor(database,"dev-actor",properties)){}})
                .hasStackTraceContaining("Development workspace writes require explicit loopback origins.");
    }

    @Test void readonlyDbCannotStartWriteModeAndDirectServiceHasValidation() throws Exception {
        database.sql("REVOKE INSERT ON flowdesk.workspaces,flowdesk.workspace_memberships FROM flowdesk_app");
        try {
            assertThatThrownBy(()->{try(var ignored=startFor(database,"dev-actor",options())){}})
                    .hasStackTraceContaining("Development workspace writes require approved minimum privileges");
        } finally { database.sql("GRANT INSERT ON flowdesk.workspaces,flowdesk.workspace_memberships TO flowdesk_app");database.grantRename().success(); }
        assertThatThrownBy(()->dave.getBean(WorkspaceCommandService.class).create(" "))
                .isInstanceOf(com.flowdesk.workspace.domain.InvalidWorkspaceNameException.class);
    }
}
