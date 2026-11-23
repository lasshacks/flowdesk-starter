package com.flowdesk.workspace;

import com.flowdesk.support.IsolatedReadDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.flowdesk.workspace.WorkspaceReadApiTest.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkspaceMembershipManagementApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("membership_api").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static String workspaceId, aliceId, bobId, eveId;
    static String taskId, projectId, activityId;
    static final String ORIGIN = "http://127.0.0.1:5173";

    @BeforeAll static void prepare() throws Exception {
        db = new IsolatedReadDatabase(POSTGRES, true);
        db.grantCreate().success(); db.grantRename().success(); db.grantProject().success(); db.grantProjectUpdate().success();
        db.grantTask().success(); db.grantTaskUpdate().success(); db.grantTaskStatusChange().success();
        db.grantTaskStatusHistoryRead().success(); db.grantTaskAssigneeDueDate().success();
        db.grantWorkspaceMemberListRead().success(); db.grantWorkspaceMembershipManagement().success();
        workspaceId = db.query("SELECT id FROM flowdesk.workspaces WHERE name='FlowDesk Demo A'");
        aliceId = db.query("SELECT id FROM flowdesk.users WHERE email='alice@example.test'");
        bobId = db.query("SELECT id FROM flowdesk.users WHERE email='bob@example.test'");
        eveId = db.query("SELECT id FROM flowdesk.users WHERE email='eve@example.test'");
        alice = startFor(db, "dev-actor", options("alice"));
    }

    @AfterEach void restoreSeed() throws Exception {
        db.sql("DELETE FROM flowdesk.workspace_invitations");
        if (activityId != null) db.sql("DELETE FROM flowdesk.task_status_activities WHERE id=" + activityId);
        if (taskId != null) db.sql("DELETE FROM flowdesk.tasks WHERE id=" + taskId);
        if (projectId != null) db.sql("DELETE FROM flowdesk.projects WHERE id=" + projectId);
        activityId = null; taskId = null; projectId = null;
        db.sql("DELETE FROM flowdesk.workspace_memberships WHERE workspace_id=" + workspaceId + " AND user_id=" + eveId);
        db.sql("UPDATE flowdesk.workspace_memberships SET role='ADMIN' WHERE workspace_id=" + workspaceId + " AND user_id=" + bobId);
        db.sql("UPDATE flowdesk.workspace_memberships SET role='MEMBER' WHERE workspace_id=" + workspaceId + " AND user_id=(SELECT id FROM flowdesk.users WHERE email='carol@example.test')");
        db.sql("UPDATE flowdesk.workspace_memberships SET role='OWNER' WHERE workspace_id=" + workspaceId + " AND user_id=" + aliceId);
        db.sql("UPDATE flowdesk.workspace_memberships SET role='VIEWER' WHERE workspace_id=" + workspaceId + " AND user_id=(SELECT id FROM flowdesk.users WHERE email='dave@example.test')");
    }

    @AfterAll static void finish() { if (alice != null) alice.close(); }

    @Test void invitationReissueAcceptRoleRemovalAndOwnershipTransferUseServerActorAndDigest() throws Exception {
        String issuePath = "/api/workspaces/" + workspaceId + "/invitations";
        var first = post(alice, issuePath, "{\"email\":\" Eve@Example.Test \",\"role\":\"MEMBER\"}");
        assertThat(first.statusCode()).isEqualTo(201);
        JsonNode firstBody = body(first);
        String firstCode = firstBody.path("code").asText();
        assertThat(firstCode).isNotBlank();
        assertThat(db.query("SELECT token_digest<>" + quote(firstCode) + " FROM flowdesk.workspace_invitations WHERE id=" + firstBody.path("id").asText())).isEqualTo("t");
        assertThat(db.query("SELECT status FROM flowdesk.workspace_invitations WHERE id=" + firstBody.path("id").asText())).isEqualTo("PENDING");

        var second = post(alice, issuePath, "{\"email\":\"eve@example.test\",\"role\":\"ADMIN\"}");
        assertThat(second.statusCode()).isEqualTo(201);
        String code = body(second).path("code").asText();
        assertThat(db.query("SELECT status FROM flowdesk.workspace_invitations WHERE id=" + firstBody.path("id").asText())).isEqualTo("REVOKED");
        try (var eve = startFor(db, "dev-actor", options("eve"))) {
            problem(post(eve, "/api/workspace-invitations/accept", json("code", firstCode)), 404, "WORKSPACE_INVITATION_UNAVAILABLE");
            var accepted = post(eve, "/api/workspace-invitations/accept", json("code", code));
            assertThat(accepted.statusCode()).isEqualTo(201);
            assertThat(body(accepted).path("id").asText()).isEqualTo(eveId);
            assertThat(body(accepted).path("role").asText()).isEqualTo("ADMIN");
            problem(post(eve, "/api/workspace-invitations/accept", json("code", code)), 404, "WORKSPACE_INVITATION_UNAVAILABLE");
        }
        assertThat(db.query("SELECT status FROM flowdesk.workspace_invitations WHERE token_digest=" + quote(digest(code)))).isEqualTo("ACCEPTED");
        projectId = db.query("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(" + workspaceId + ",'Membership test') RETURNING id");
        taskId = db.query("INSERT INTO flowdesk.tasks(project_id,title,assignee_id) VALUES(" + projectId + ",'Retained assignment'," + eveId + ") RETURNING id");
        activityId = db.query("INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at) VALUES(" + taskId + "," + eveId + ",'TODO','IN_PROGRESS',1,CURRENT_TIMESTAMP) RETURNING id");

        var changed = patch(alice, "/api/workspaces/" + workspaceId + "/members/" + eveId, "{\"role\":\"VIEWER\"}");
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(body(changed).path("role").asText()).isEqualTo("VIEWER");
        var removed = delete(alice, "/api/workspaces/" + workspaceId + "/members/" + eveId);
        assertThat(removed.statusCode()).isEqualTo(204);
        assertThat(db.query("SELECT count(*) FROM flowdesk.workspace_memberships WHERE workspace_id=" + workspaceId + " AND user_id=" + eveId)).isEqualTo("0");
        assertThat(db.query("SELECT assignee_id FROM flowdesk.tasks WHERE id=" + taskId)).isEqualTo(eveId);
        assertThat(db.query("SELECT actor_user_id FROM flowdesk.task_status_activities WHERE id=" + activityId)).isEqualTo(eveId);

        var transfer = post(alice, "/api/workspaces/" + workspaceId + "/ownership-transfer", json("targetUserId", bobId));
        assertThat(transfer.statusCode()).isEqualTo(200);
        assertThat(body(transfer).path("ownerUserId").asText()).isEqualTo(bobId);
        assertThat(db.query("SELECT count(*) FROM flowdesk.workspace_memberships WHERE workspace_id=" + workspaceId + " AND role='OWNER'")).isEqualTo("1");
        assertThat(db.query("SELECT role FROM flowdesk.workspace_memberships WHERE workspace_id=" + workspaceId + " AND user_id=" + aliceId)).isEqualTo("ADMIN");
        assertThat(db.query("SELECT role FROM flowdesk.workspace_memberships WHERE workspace_id=" + workspaceId + " AND user_id=" + bobId)).isEqualTo("OWNER");
    }

    @Test void managerCanCancelPendingInvitationWithoutRevealingItToOtherActors() throws Exception {
        String path = "/api/workspaces/" + workspaceId + "/invitations";
        var issued = post(alice, path, "{\"email\":\"eve@example.test\",\"role\":\"MEMBER\"}");
        assertThat(issued.statusCode()).isEqualTo(201);
        String invitationId = body(issued).path("id").asText();
        assertThat(delete(alice, path + "/" + invitationId).statusCode()).isEqualTo(204);
        assertThat(db.query("SELECT status FROM flowdesk.workspace_invitations WHERE id=" + invitationId)).isEqualTo("REVOKED");
        try (var eve = startFor(db, "dev-actor", options("eve"))) {
            problem(post(eve, "/api/workspace-invitations/accept", json("code", body(issued).path("code").asText())),
                    404, "WORKSPACE_INVITATION_UNAVAILABLE");
        }
    }

    @Test void pendingInvitationListIsManagerOnlyPagedNewestFirstAndExcludesSecretsAndHistory() throws Exception {
        String workspaceBId = db.query("SELECT id FROM flowdesk.workspaces WHERE name='FlowDesk Demo B'");
        String expiredId = insertInvitation(workspaceId, eveId, aliceId, "MEMBER", "1", "PENDING",
                "CURRENT_TIMESTAMP-INTERVAL '8 days'", "CURRENT_TIMESTAMP-INTERVAL '1 day'", "NULL");
        String activeId = insertInvitation(workspaceId, bobId, aliceId, "ADMIN", "2", "PENDING",
                "CURRENT_TIMESTAMP-INTERVAL '1 hour'", "CURRENT_TIMESTAMP+INTERVAL '6 days'", "NULL");
        insertInvitation(workspaceId, db.query("SELECT id FROM flowdesk.users WHERE email='carol@example.test'"), aliceId,
                "VIEWER", "3", "ACCEPTED", "CURRENT_TIMESTAMP-INTERVAL '2 days'", "CURRENT_TIMESTAMP+INTERVAL '5 days'", "CURRENT_TIMESTAMP");
        insertInvitation(workspaceId, db.query("SELECT id FROM flowdesk.users WHERE email='dave@example.test'"), aliceId,
                "MEMBER", "4", "REVOKED", "CURRENT_TIMESTAMP-INTERVAL '2 days'", "CURRENT_TIMESTAMP+INTERVAL '5 days'", "NULL");
        insertInvitation(workspaceBId, eveId, eveId, "MEMBER", "5", "PENDING",
                "CURRENT_TIMESTAMP-INTERVAL '1 hour'", "CURRENT_TIMESTAMP+INTERVAL '6 days'", "NULL");

        var first = call(alice, "/api/workspaces/" + workspaceId + "/invitations?size=1");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("cache-control")).contains("no-store");
        var firstBody = body(first);
        assertThat(firstBody.path("items").size()).isEqualTo(1);
        assertThat(firstBody.path("items").get(0).path("id").asText()).isEqualTo(activeId);
        assertThat(firstBody.path("items").get(0).path("targetDisplayName").asText()).isEqualTo("Bob");
        assertThat(firstBody.path("items").get(0).path("targetEmail").asText()).isEqualTo("bob@example.test");
        assertThat(firstBody.path("items").get(0).path("role").asText()).isEqualTo("ADMIN");
        assertThat(firstBody.path("items").get(0).path("expired").asBoolean()).isFalse();
        assertThat(firstBody.path("page").asInt()).isZero();
        assertThat(firstBody.path("size").asInt()).isEqualTo(1);
        assertThat(firstBody.path("hasNext").asBoolean()).isTrue();
        assertThat(firstBody.toString()).doesNotContain("tokenDigest", "targetUserId", "invitedByUserId", "code");

        var second = body(call(alice, "/api/workspaces/" + workspaceId + "/invitations?page=1&size=1"));
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("items").get(0).path("id").asText()).isEqualTo(expiredId);
        assertThat(second.path("items").get(0).path("targetEmail").asText()).isEqualTo("eve@example.test");
        assertThat(second.path("items").get(0).path("expired").asBoolean()).isTrue();
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        var defaults = body(call(alice, "/api/workspaces/" + workspaceId + "/invitations"));
        assertThat(defaults.path("page").asInt()).isZero();
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        assertThat(defaults.path("items").size()).isEqualTo(2);

        try (var bob = startFor(db, "dev-actor", options("bob"))) {
            assertThat(call(bob, "/api/workspaces/" + workspaceId + "/invitations").statusCode()).isEqualTo(200);
        }
        try (var carol = startFor(db, "dev-actor", options("carol"));
                var dave = startFor(db, "dev-actor", options("dave"));
                var eve = startFor(db, "dev-actor", options("eve"))) {
            problem(call(carol, "/api/workspaces/" + workspaceId + "/invitations"), 403, "WORKSPACE_FORBIDDEN");
            problem(call(dave, "/api/workspaces/" + workspaceId + "/invitations"), 403, "WORKSPACE_FORBIDDEN");
            problem(call(eve, "/api/workspaces/" + workspaceId + "/invitations"), 404, "WORKSPACE_NOT_FOUND");
        }
        problem(call(alice, "/api/workspaces/999999/invitations"), 404, "WORKSPACE_NOT_FOUND");
        problem(call(alice, "/api/workspaces/" + workspaceId + "/invitations?page=0&page=1"), 400, "INVALID_REQUEST");
        problem(call(alice, "/api/workspaces/" + workspaceId + "/invitations?size=101"), 400, "INVALID_REQUEST");
        problem(call(alice, "/api/workspaces/" + workspaceId + "/invitations?unknown=1"), 400, "INVALID_REQUEST");
    }

    private static String insertInvitation(String workspace, String target, String inviter, String role, String digest,
            String status, String createdAt, String expiresAt, String acceptedAt) throws Exception {
        return db.query("INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,status,created_at,expires_at,accepted_at) VALUES(" +
                workspace + "," + target + "," + inviter + ",'" + role + "','" + digest.repeat(64) + "','" + status + "'," +
                createdAt + "," + expiresAt + "," + acceptedAt + ") RETURNING id");
    }

    @Test void managerRoleBoundaryAndWorkspaceOriginBoundaryAreEnforced() throws Exception {
        String path = "/api/workspaces/" + workspaceId + "/invitations";
        try (var carol = startFor(db, "dev-actor", options("carol")); var eve = startFor(db, "dev-actor", options("eve"))) {
            problem(post(carol, path, "{\"email\":\"eve@example.test\",\"role\":\"MEMBER\"}"), 403, "WORKSPACE_FORBIDDEN");
            problem(post(eve, path, "{\"email\":\"eve@example.test\",\"role\":\"MEMBER\"}"), 404, "WORKSPACE_NOT_FOUND");
        }
        var denied = raw(alice, "POST", path, "{\"email\":\"eve@example.test\",\"role\":\"MEMBER\"}", "Origin", "http://evil.test", "Content-Type", "application/json");
        problem(denied, 403, "DEV_ACCESS_DENIED");
        var ownerInvite = post(alice, path, "{\"email\":\"bob@example.test\",\"role\":\"OWNER\"}");
        problem(ownerInvite, 400, "INVALID_REQUEST");
        var issued = post(alice, path, "{\"email\":\"eve@example.test\",\"role\":\"MEMBER\"}");
        String code = body(issued).path("code").asText();
        try (var dave = startFor(db, "dev-actor", options("dave"))) {
            problem(post(dave, "/api/workspace-invitations/accept", json("code", code)), 404, "WORKSPACE_INVITATION_UNAVAILABLE");
        }
        db.sql("UPDATE flowdesk.workspace_invitations SET created_at=CURRENT_TIMESTAMP-INTERVAL '8 days',expires_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE token_digest=" + quote(digest(code)));
        try (var eve = startFor(db, "dev-actor", options("eve"))) {
            problem(post(eve, "/api/workspace-invitations/accept", json("code", code)), 404, "WORKSPACE_INVITATION_UNAVAILABLE");
        }
        try (var bob = startFor(db, "dev-actor", options("bob"))) {
            problem(patch(bob, "/api/workspaces/" + workspaceId + "/members/" + aliceId, "{\"role\":\"ADMIN\"}"), 409, "WORKSPACE_MEMBERSHIP_CONFLICT");
            problem(delete(bob, "/api/workspaces/" + workspaceId + "/members/" + aliceId), 409, "WORKSPACE_MEMBERSHIP_CONFLICT");
        }
    }

    @Test void concurrentOwnershipTransfersCommitOneOwnerAndOneSuccessfulTransfer() throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var toBob = pool.submit(() -> { gate.await(); return post(alice, "/api/workspaces/" + workspaceId + "/ownership-transfer", json("targetUserId", bobId)); });
            var toCarol = pool.submit(() -> { gate.await(); return post(alice, "/api/workspaces/" + workspaceId + "/ownership-transfer", json("targetUserId", db.query("SELECT id FROM flowdesk.users WHERE email='carol@example.test'"))); });
            gate.countDown();
            var first = toBob.get(); var second = toCarol.get();
            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(200, 403);
            problem(first.statusCode() == 403 ? first : second, 403, "WORKSPACE_FORBIDDEN");
        }
        assertThat(db.query("SELECT count(*) FROM flowdesk.workspace_memberships WHERE workspace_id=" + workspaceId + " AND role='OWNER'")).isEqualTo("1");
        String carolId = db.query("SELECT id FROM flowdesk.users WHERE email='carol@example.test'");
        String ownerId = db.query("SELECT user_id FROM flowdesk.workspace_memberships WHERE workspace_id=" + workspaceId + " AND role='OWNER'");
        assertThat(List.of(bobId, carolId)).contains(ownerId);
    }

    private static Map<String,Object> options(String user) {
        return Map.of("FLOWDESK_DEV_ACTOR_EMAIL", user + "@example.test", "flowdesk.dev.workspace-write-enabled", "true",
                "FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS", ORIGIN);
    }
    private static HttpResponse<String> post(ConfigurableApplicationContext context, String path, String body) throws Exception {
        return raw(context, "POST", path, body, "Origin", ORIGIN, "Content-Type", "application/json");
    }
    private static HttpResponse<String> patch(ConfigurableApplicationContext context, String path, String body) throws Exception {
        return raw(context, "PATCH", path, body, "Origin", ORIGIN, "Content-Type", "application/json");
    }
    private static HttpResponse<String> delete(ConfigurableApplicationContext context, String path) throws Exception {
        return raw(context, "DELETE", path, "", "Origin", ORIGIN);
    }
    private static HttpResponse<String> raw(ConfigurableApplicationContext context, String method, String path, String body, String... headers) throws Exception {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String json(String key, String value) throws Exception { return JSON.writeValueAsString(Map.of(key, value)); }
    private static String quote(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static String digest(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
}
