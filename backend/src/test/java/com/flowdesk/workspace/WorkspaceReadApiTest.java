package com.flowdesk.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.FlowDeskApplication;
import com.flowdesk.support.IsolatedReadDatabase;
import com.flowdesk.workspace.api.DevWorkspaceAccessFilter;
import com.flowdesk.workspace.application.WorkspaceQueryService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
public class WorkspaceReadApiTest {
    @Container
    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("api_tests").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    public static IsolatedReadDatabase db;
    public static ConfigurableApplicationContext alice;
    public static String a, b, original;
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .proxy(new ProxySelector() {
                public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                public void connectFailed(URI uri, SocketAddress address, java.io.IOException error) { }
            }).build();

    @BeforeAll
    public static void prepare() throws Exception {
        db = new IsolatedReadDatabase(POSTGRES, true);
        db.grantCreate().success(); db.grantRename().success(); db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();db.grantTaskStatusHistoryRead().success();db.grantTaskAssigneeDueDate().success();db.grantWorkspaceMemberListRead().success();
        a = db.query("SELECT id FROM flowdesk.workspaces WHERE name='FlowDesk Demo A'");
        b = db.query("SELECT id FROM flowdesk.workspaces WHERE name='FlowDesk Demo B'");
        original = db.snapshot();
        alice = start("dev-actor", Map.of("FLOWDESK_DEV_ACTOR_EMAIL", " Alice@Example.Test "));
    }

    @AfterAll
    public static void finish() throws Exception {
        if (alice != null) alice.close();
        if (db != null) assertThat(db.snapshot()).isEqualTo(original);
    }

    public static ConfigurableApplicationContext start(String profile, Map<String, Object> overrides) {
        return startFor(db, profile, overrides);
    }

    public static ConfigurableApplicationContext startFor(IsolatedReadDatabase fixture, String profile, Map<String, Object> overrides) {
        var properties = new HashMap<String, Object>();
        properties.put("spring.config.import", "");
        properties.put("FLOWDESK_DEV_ACTOR_EMAIL", "");
        properties.put("spring.flyway.enabled", "false");
        properties.put("spring.profiles.active", profile);
        properties.put("spring.datasource.url", fixture.url);
        properties.put("flowdesk.dev.workspace-write-enabled", "false");
        properties.put("FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS", "");
        properties.put("spring.datasource.username", "flowdesk_app");
        properties.put("spring.datasource.password", IsolatedReadDatabase.APP_PASSWORD);
        properties.put("spring.datasource.hikari.connection-timeout", "1000");
        properties.put("spring.datasource.hikari.data-source-properties.socketTimeout", "1");
        properties.put("server.port", "0");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.forward-headers-strategy", "none");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "OFF");
        properties.put("logging.level.com.flowdesk.workspace.api.WorkspaceErrors", "ERROR");
        properties.put("spring.jpa.properties.hibernate.generate_statistics", "true");
        properties.putAll(overrides);
        return new SpringApplicationBuilder(FlowDeskApplication.class).profiles(profile)
                .initializers(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("isolated-api-test", properties)))
                .run("--spring.config.import=");
    }

    public static HttpResponse<String> call(ConfigurableApplicationContext context, String path, String... headers) throws Exception {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public static JsonNode body(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()); }
    public static void problem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        var json = body(response);
        assertThat(json.path("status").asInt()).isEqualTo(status);
        assertThat(json.path("code").asText()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("example.test", "SQLException", "jdbc:", "flowdesk_app", "SELECT ");
    }

    @Test
    void listAndDetailExposeOnlyDtoAndUseAppRole() throws Exception {
        var response = call(alice, "/api/workspaces");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        var list = body(response);
        assertThat(list.size()).isEqualTo(4);
        assertThat(list.path("items").size()).isEqualTo(1);
        assertThat(list.path("page").asInt()).isZero();
        assertThat(list.path("size").asInt()).isEqualTo(20);
        assertThat(list.path("hasNext").asBoolean()).isFalse();
        var detail = body(call(alice, "/api/workspaces/" + a));
        assertThat(detail.size()).isEqualTo(4);
        assertThat(detail.path("version").asText()).isEqualTo("0");
        assertThat(detail.path("id").isTextual()).isTrue();
        assertThat(detail.path("id").asText()).isEqualTo(a);
        assertThat(detail.path("name").asText()).isEqualTo("FlowDesk Demo A");
        assertThat(detail.path("myRole").asText()).isEqualTo("OWNER");
        assertThat(list.path("items").get(0)).isEqualTo(detail);
        var source = alice.getBean(javax.sql.DataSource.class);
        try (var connection = source.getConnection(); var rows = connection.createStatement().executeQuery("SELECT current_user")) {
            rows.next(); assertThat(rows.getString(1)).isEqualTo("flowdesk_app");
        }
        assertThat(alice.getEnvironment().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }

    @ParameterizedTest
    @CsvSource({"bob,ADMIN,A", "carol,MEMBER,A", "dave,VIEWER,A", "eve,OWNER,B"})
    void rolesAndOtherTeamAreCheckedByServerActor(String name, String role, String team) throws Exception {
        try (var context = start("dev-actor", Map.of("FLOWDESK_DEV_ACTOR_EMAIL", name + "@example.test"))) {
            String own = team.equals("A") ? a : b, other = team.equals("A") ? b : a;
            var list = body(call(context, "/api/workspaces"));
            assertThat(list.path("items").size()).isEqualTo(1);
            assertThat(list.path("items").get(0).path("id").asText()).isEqualTo(own);
            assertThat(body(call(context, "/api/workspaces/" + own)).path("myRole").asText()).isEqualTo(role);
            assertThat(call(context, "/api/workspaces/" + own + "/members").statusCode()).isEqualTo(200);
            problem(call(context, "/api/workspaces/" + other), 404, "WORKSPACE_NOT_FOUND");
        }
    }

    @Test
    void memberListReturnsOnlyCurrentWorkspaceIdentityFieldsInStablePages() throws Exception {
        var firstResponse = call(alice, "/api/workspaces/" + a + "/members?page=0&size=2");
        assertThat(firstResponse.statusCode()).isEqualTo(200);
        assertThat(firstResponse.headers().firstValue("cache-control")).contains("no-store");
        var first = body(firstResponse);
        assertThat(first.size()).isEqualTo(4);
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        for (var item : first.path("items")) {
            assertThat(item.size()).isEqualTo(3);
            assertThat(item.path("id").isTextual()).isTrue();
            assertThat(item.path("displayName").isTextual()).isTrue();
            assertThat(item.path("role").asText()).isIn("OWNER", "ADMIN", "MEMBER", "VIEWER");
            assertThat(item.has("email")).isFalse();
        }
        var second = body(call(alice, "/api/workspaces/" + a + "/members?page=1&size=2"));
        assertThat(second.path("items").size()).isEqualTo(2);
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        assertThat(first.path("items").get(1).path("id").asLong())
                .isLessThan(second.path("items").get(0).path("id").asLong());
        problem(call(alice, "/api/workspaces/" + b + "/members"), 404, "WORKSPACE_NOT_FOUND");
        problem(call(alice, "/api/workspaces/9223372036854775807/members"), 404, "WORKSPACE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=101", "?size=2&size=2", "?email=true"})
    void memberListRejectsInvalidPaginationAndExtraParameters(String query) throws Exception {
        problem(call(alice, "/api/workspaces/" + a + "/members" + query), 400, "INVALID_REQUEST");
    }

    @Test
    void absentAndForeignIdsHaveSameStatusCodeAndDetail() throws Exception {
        var foreign = call(alice, "/api/workspaces/" + b);
        var absent = call(alice, "/api/workspaces/9223372036854775807");
        problem(foreign, 404, "WORKSPACE_NOT_FOUND");
        problem(absent, 404, "WORKSPACE_NOT_FOUND");
        for (String field : List.of("status", "code", "title", "detail", "type")) {
            assertThat(body(foreign).path(field)).isEqualTo(body(absent).path(field));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?page=10001", "?page=x", "?page=", "?size=0", "?size=101",
            "?size=1.5", "?size=99999999999999999999", "?page=0&page=1", "?size=2&size=2",
            "?userId=5", "?actorId=5", "?role=OWNER", "?sort=name", "/0", "/-1", "/abc",
            "/9223372036854775808", "/1?userId=5", "/1?page=0"})
    void rejectsInvalidAndIdentitySelectingInputs(String suffix) throws Exception {
        problem(call(alice, "/api/workspaces" + suffix), 400, "INVALID_REQUEST");
    }

    @Test
    void clientHeadersCannotSelectActorOrOverridePeer() throws Exception {
        var response = call(alice, "/api/workspaces", "X-User-Id", "5", "X-Actor-Id", "5",
                "X-Role", "OWNER", "Authorization", "Bearer fake-token", "Cookie", "userId=5",
                "X-Forwarded-For", "203.0.113.10", "Forwarded", "for=203.0.113.10");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("items").get(0).path("id").asText()).isEqualTo(a);
    }

    @Test
    void nonLoopbackCannotSpoofForwardedPeer() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/workspaces");
        request.setServletPath("/api/workspaces");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("Forwarded", "for=127.0.0.1");
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean(false);
        alice.getBean(DevWorkspaceAccessFilter.class).doFilter(request, response, (req, res) -> reached.set(true));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(reached).isFalse();
    }

    @Test
    void defaultModeDoesNotRegisterBusinessApiEvenWithActorSetting() throws Exception {
        try (var context = start("default", Map.of("FLOWDESK_DEV_ACTOR_EMAIL", "alice@example.test"))) {
            assertThat(context.getBeansOfType(WorkspaceQueryService.class)).isEmpty();
            assertThat(call(context, "/api/workspaces").statusCode()).isEqualTo(404);
            assertThat(call(context, "/api/workspaces/" + a).statusCode()).isEqualTo(404);
            assertThat(call(context, "/api/health").statusCode()).isEqualTo(200);
            assertThat(call(context, "/actuator/health").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void profileWithoutActorFailsAndDirectServiceCannotBypassMembership() {
        assertThatThrownBy(() -> { try (var ignored = start("dev-actor", Map.of())) { } })
                .hasStackTraceContaining("Development actor requires an existing configured user.");
        assertThatThrownBy(() -> alice.getBean(WorkspaceQueryService.class).detail(Long.parseLong(b)))
                .isInstanceOf(com.flowdesk.workspace.application.WorkspaceNotFoundException.class);
    }

    @Test
    void unexpectedDatabasePermissionErrorIsSanitized500() throws Exception {
        try {
            db.sql("REVOKE SELECT ON flowdesk.workspaces FROM flowdesk_app");
            problem(call(alice, "/api/workspaces"), 500, "INTERNAL_ERROR");
        } finally { db.sql("GRANT SELECT ON flowdesk.workspaces TO flowdesk_app"); }
        assertThat(call(alice, "/api/workspaces").statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @CsvSource(value = {"FLOWDESK_DEV_ACTOR_EMAIL| ", "FLOWDESK_DEV_ACTOR_EMAIL|missing@example.test",
            "server.address|0.0.0.0", "server.address|localhost", "server.address| ",
            "server.forward-headers-strategy|native", "server.forward-headers-strategy|framework",
            "server.tomcat.remoteip.remote-ip-header|X-Forwarded-For"}, delimiter = '|', emptyValue = "")
    void invalidDevelopmentConfigurationFailsStartup(String key, String value) {
        var config = new HashMap<String, Object>();
        config.put("FLOWDESK_DEV_ACTOR_EMAIL", "alice@example.test");
        config.put(key, value == null ? "" : value);
        assertThatThrownBy(() -> { try (var ignored = start("dev-actor", config)) { } })
                .hasStackTraceContaining("Development actor requires");
    }

    @Test
    void paginationAndFetchGraphAvoidWorkspaceNPlusOne() throws Exception {
        try {
            db.sql("""
                    WITH w AS (INSERT INTO flowdesk.workspaces(name) VALUES ('API Extra 1'),('API Extra 2') RETURNING id)
                    INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role)
                    SELECT w.id,u.id,'OWNER' FROM w CROSS JOIN flowdesk.users u WHERE u.email='alice@example.test'
                    """);
            var statistics = alice.getBean(EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            var first = body(call(alice, "/api/workspaces?page=0&size=2"));
            assertThat(first.path("items").size()).isEqualTo(2);
            assertThat(first.path("hasNext").asBoolean()).isTrue();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2); // actor + membership/workspace Slice
            var second = body(call(alice, "/api/workspaces?page=1&size=2"));
            assertThat(second.path("items").size()).isEqualTo(1);
            assertThat(second.path("hasNext").asBoolean()).isFalse();
            assertThat(second.path("items").get(0).path("id").asLong())
                    .isGreaterThan(first.path("items").get(1).path("id").asLong());
            var empty = body(call(alice, "/api/workspaces?page=10000&size=100"));
            assertThat(empty.path("items").size()).isZero();
            assertThat(empty.path("hasNext").asBoolean()).isFalse();
        } finally {
            db.sql("DELETE FROM flowdesk.workspace_memberships WHERE workspace_id IN (SELECT id FROM flowdesk.workspaces WHERE name LIKE 'API Extra %')");
            db.sql("DELETE FROM flowdesk.workspaces WHERE name LIKE 'API Extra %'");
        }
    }

    @Test
    void noMembershipAndRemovedActorFailWithoutFallback() throws Exception {
        try {
            db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES ('empty@example.test','Empty')");
            try (var context = start("dev-actor", Map.of("FLOWDESK_DEV_ACTOR_EMAIL", "empty@example.test"))) {
                var empty = body(call(context, "/api/workspaces"));
                assertThat(empty.path("items").size()).isZero();
                assertThat(empty.path("hasNext").asBoolean()).isFalse();
                db.sql("DELETE FROM flowdesk.users WHERE email='empty@example.test'");
                problem(call(context, "/api/workspaces"), 503, "SERVICE_UNAVAILABLE");
            }
        } finally { db.sql("DELETE FROM flowdesk.users WHERE email='empty@example.test'"); }
    }

    @Test
    void databaseOutageReturns503AndRecovers() throws Exception {
        assertThat(call(alice, "/api/workspaces").statusCode()).isEqualTo(200);
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            problem(call(alice, "/api/workspaces"), 503, "SERVICE_UNAVAILABLE");
        } finally { POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec(); }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(call(alice, "/api/workspaces").statusCode()).isEqualTo(200));
    }
}
