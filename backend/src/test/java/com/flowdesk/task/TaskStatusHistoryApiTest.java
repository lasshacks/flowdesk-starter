package com.flowdesk.task;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Instant;
import java.util.*;
import static com.flowdesk.workspace.WorkspaceReadApiTest.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TaskStatusHistoryApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("status_history").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase db;
    static ConfigurableApplicationContext alice;
    static final String PATH="/api/workspaces/1/projects/1/tasks/1/status-activities";
    static String parents,bobCreated,taskUpdated;
    static Map<String,Object> actor(String user) {return Map.of("FLOWDESK_DEV_ACTOR_EMAIL",user+"@example.test");}
    @BeforeAll static void prepare() throws Exception {
        db=new IsolatedReadDatabase(POSTGRES,true);
        db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();
        db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();db.grantTaskStatusHistoryRead().success();db.grantTaskAssigneeDueDate().success();
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Parent'),(2,'Foreign'); INSERT INTO flowdesk.tasks(project_id,title,status,version) VALUES(1,'Task','DONE',14),(2,'Foreign','DONE',14),(1,'Empty','TODO',0)");
        db.sql("""
                INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at)
                SELECT 1,3,f,t,v,TIMESTAMPTZ '2026-11-10 00:00:00.123456+00'+v*INTERVAL '1 second'
                FROM (VALUES(1,'TODO','IN_PROGRESS'),(3,'IN_PROGRESS','DONE'),(8,'DONE','TODO'),(10,'TODO','IN_PROGRESS'),(14,'IN_PROGRESS','DONE')) x(v,f,t);
                INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at)
                VALUES(2,5,'TODO','DONE',14,CURRENT_TIMESTAMP)
                """);
        bobCreated=db.query("SELECT created_at::text FROM flowdesk.workspace_memberships WHERE id=2");
        taskUpdated=db.query("SELECT updated_at::text FROM flowdesk.tasks WHERE id=1");
        parents=db.snapshot()+db.query("SELECT json_agg(p ORDER BY id)::text FROM flowdesk.projects p");
        alice=startFor(db,"dev-actor",actor("alice"));
    }
    @AfterAll static void close(){if(alice!=null)alice.close();}
    @AfterEach void restore() throws Exception {
        db.sql("DELETE FROM flowdesk.task_status_activities WHERE task_id NOT IN(1,2) OR (task_id=1 AND task_version NOT IN(1,3,8,10,14)); DELETE FROM flowdesk.tasks WHERE id>3; DELETE FROM flowdesk.users WHERE id>5; UPDATE flowdesk.tasks SET status='DONE',version=14,updated_at='"+taskUpdated+"'::timestamptz WHERE id=1; UPDATE flowdesk.users SET display_name='Carol' WHERE id=3; DELETE FROM flowdesk.workspace_memberships WHERE user_id=1 AND workspace_id=2; UPDATE flowdesk.workspace_memberships SET workspace_id=1 WHERE id IN(2,3); UPDATE flowdesk.workspace_memberships SET created_at='"+bobCreated+"'::timestamptz WHERE id=2; ALTER ROLE flowdesk_app RESET timezone");
        assertThat(db.snapshot()+db.query("SELECT json_agg(p ORDER BY id)::text FROM flowdesk.projects p")).isEqualTo(parents);
    }
    static List<String> versions(com.fasterxml.jackson.databind.JsonNode page) {
        var result=new ArrayList<String>();page.path("items").forEach(x->result.add(x.path("taskVersion").asText()));return result;
    }
    @ParameterizedTest @ValueSource(strings={"alice","bob","carol","dave"})
    void allFourRolesReadExactDtoWithoutEmail(String user) throws Exception {
        try(var ctx=startFor(db,"dev-actor",actor(user))) {
            var response=call(ctx,PATH);assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/json");
            assertThat(response.headers().firstValue("cache-control")).contains("no-store");
            var page=body(response);assertThat(page.size()).isEqualTo(8);
            assertThat(page.path("workspaceId").asText()).isEqualTo("1");assertThat(page.path("projectId").asText()).isEqualTo("1");assertThat(page.path("taskId").asText()).isEqualTo("1");
            assertThat(page.path("beforeVersion").isNull()).isTrue();assertThat(page.path("size").asInt()).isEqualTo(20);
            assertThat(page.path("hasNext").asBoolean()).isFalse();assertThat(page.path("nextBeforeVersion").isNull()).isTrue();
            assertThat(versions(page)).containsExactly("14","10","8","3","1");
            for(var item:page.path("items")) {
                assertThat(item.size()).isEqualTo(6);assertThat(item.path("id").isTextual()).isTrue();
                assertThat(item.path("actor").size()).isEqualTo(2);assertThat(item.path("actor").path("id").asText()).isEqualTo("3");
                assertThat(item.path("actor").path("displayName").asText()).isEqualTo("Carol");
                assertThat(item.path("fromStatus").asText()).isNotEqualTo(item.path("toStatus").asText());
                assertThat(Instant.parse(item.path("occurredAt").asText())).isEqualTo(Instant.parse("2026-11-10T00:00:00.123456Z").plusSeconds(Long.parseLong(item.path("taskVersion").asText())));
            }
            assertThat(response.body()).doesNotContain("email","example.test","workspaceMembership","currentRole");
        }
    }
    @Test void keysetPagesGapsAndEmptyEchoAreConsistent() throws Exception {
        var one=body(call(alice,PATH+"?size=2"));assertThat(versions(one)).containsExactly("14","10");
        assertThat(one.path("hasNext").asBoolean()).isTrue();assertThat(one.path("nextBeforeVersion").asText()).isEqualTo("10");
        var two=body(call(alice,PATH+"?beforeVersion=10&size=2"));assertThat(versions(two)).containsExactly("8","3");
        assertThat(two.path("beforeVersion").asText()).isEqualTo("10");assertThat(two.path("nextBeforeVersion").asText()).isEqualTo("3");
        var three=body(call(alice,PATH+"?size=2&beforeVersion=3"));assertThat(versions(three)).containsExactly("1");
        assertThat(three.path("hasNext").asBoolean()).isFalse();assertThat(three.path("nextBeforeVersion").isNull()).isTrue();
        assertThat(versions(body(call(alice,PATH+"?beforeVersion=9&size=2")))).containsExactly("8","3");
        assertThat(versions(body(call(alice,PATH+"?beforeVersion=9223372036854775807&size=100")))).hasSize(5);
        var empty=body(call(alice,PATH+"?beforeVersion=1&size=1"));assertThat(empty.path("items").isEmpty()).isTrue();
        assertThat(empty.path("beforeVersion").asText()).isEqualTo("1");assertThat(empty.path("size").asInt()).isEqualTo(1);
        assertThat(empty.path("hasNext").asBoolean()).isFalse();assertThat(empty.path("nextBeforeVersion").isNull()).isTrue();
        var noHistory=call(alice,PATH.replace("tasks/1/","tasks/3/"));assertThat(noHistory.statusCode()).isEqualTo(200);assertThat(body(noHistory).path("items").isEmpty()).isTrue();
    }
    @Test void realNewStatusCommitDoesNotShiftOlderPages() throws Exception {
        assertThat(versions(body(call(alice,PATH+"?size=2")))).containsExactly("14","10");
        try(var writer=startFor(db,"dev-actor",TaskUpdateApiTest.options("alice"))) {
            var changed=TaskUpdateApiTest.raw(writer,"/api/workspaces/1/projects/1/tasks/1/status","{\"status\":\"TODO\",\"version\":\"14\"}");
            assertThat(changed.statusCode()).isEqualTo(200);assertThat(body(changed).path("version").asText()).isEqualTo("15");
            assertThat(versions(body(call(alice,PATH+"?beforeVersion=10&size=2")))).containsExactly("8","3");
            assertThat(versions(body(call(alice,PATH+"?size=2")))).containsExactly("15","14");
            var noop=TaskUpdateApiTest.raw(writer,"/api/workspaces/1/projects/1/tasks/1/status","{\"status\":\"TODO\",\"version\":\"15\"}");
            assertThat(noop.statusCode()).isEqualTo(200);assertThat(versions(body(call(alice,PATH)))).hasSize(6);
        }
    }
    @Test void currentNameFormerActorAndNewMembershipReadPastRecords() throws Exception {
        db.sql("UPDATE flowdesk.users SET display_name='Current <name>' WHERE id=3; UPDATE flowdesk.workspace_memberships SET workspace_id=2 WHERE id=3; UPDATE flowdesk.workspace_memberships SET created_at='2030-01-01T00:00:00Z' WHERE id=2");
        var item=body(call(alice,PATH)).path("items").get(0);
        assertThat(item.path("actor").path("displayName").asText()).isEqualTo("Current <name>");
        try(var bob=startFor(db,"dev-actor",actor("bob"))){assertThat(versions(body(call(bob,PATH)))).hasSize(5);}
    }
    @Test void callerLosesMembershipAndOtherParentsStayHidden() throws Exception {
        try(var bob=startFor(db,"dev-actor",actor("bob"));var eve=startFor(db,"dev-actor",actor("eve"))) {
            assertThat(call(bob,PATH).statusCode()).isEqualTo(200);db.sql("UPDATE flowdesk.workspace_memberships SET workspace_id=2 WHERE id=2");
            problem(call(bob,PATH),404,"TASK_NOT_FOUND");
            var foreign=call(eve,PATH,"X-User-Id","1","X-Role","OWNER");var missing=call(eve,PATH.replace("tasks/1/","tasks/999/"));
            problem(foreign,404,"TASK_NOT_FOUND");problem(missing,404,"TASK_NOT_FOUND");assertThat(body(foreign).path("detail")).isEqualTo(body(missing).path("detail"));
        }
        db.sql("INSERT INTO flowdesk.workspace_memberships(user_id,workspace_id,role) VALUES(1,2,'VIEWER')");
        assertThat(call(alice,"/api/workspaces/2/projects/2/tasks/2/status-activities").statusCode()).isEqualTo(200);
        for(String path:List.of(PATH.replace("workspaces/1","workspaces/2"),PATH.replace("projects/1","projects/2"),PATH.replace("tasks/1/","tasks/2/"),PATH.replace("tasks/1/","tasks/999/")))problem(call(alice,path),404,"TASK_NOT_FOUND");
    }
    @ParameterizedTest @ValueSource(strings={"?size=0","?size=101","?size=01","?size=","?size=-1","?size=1.0","?size=9223372036854775808","?size=2&size=3","?page=0","?sort=ASC","?actorUserId=3","?beforeVersion=0","?beforeVersion=01","?beforeVersion=","?beforeVersion=-1","?beforeVersion=+1","?beforeVersion=1.0","?beforeVersion=9223372036854775808","?beforeVersion=2&beforeVersion=3","?version=14"})
    void invalidQueryIs400(String query) throws Exception {problem(call(alice,PATH+query),400,"INVALID_REQUEST");}
    @ParameterizedTest @ValueSource(strings={"0","01","-1","9223372036854775808"})
    void allPathIdsAreCanonical(String value) throws Exception {
        for(String path:List.of(PATH.replace("workspaces/1","workspaces/"+value),PATH.replace("projects/1","projects/"+value),PATH.replace("tasks/1/","tasks/"+value+"/")))problem(call(alice,path),400,"INVALID_REQUEST");
    }
    @Test void bigIdsVersionsAndUtcPrecisionAreLossless() throws Exception {
        db.sql("INSERT INTO flowdesk.users(id,email,display_name) OVERRIDING SYSTEM VALUE VALUES(9007199254740993,'large@example.test','Large actor'); INSERT INTO flowdesk.tasks(id,project_id,title,status,version) OVERRIDING SYSTEM VALUE VALUES(9007199254740994,1,'Large','DONE',9007199254740993); INSERT INTO flowdesk.task_status_activities(id,task_id,actor_user_id,from_status,to_status,task_version,occurred_at) OVERRIDING SYSTEM VALUE VALUES(9007199254740995,9007199254740994,9007199254740993,'TODO','DONE',9007199254740993,'2026-11-10T00:00:00.123456Z'); ALTER ROLE flowdesk_app SET timezone TO 'Asia/Seoul'");
        try(var dave=startFor(db,"dev-actor",actor("dave"))) {
            var page=body(call(dave,PATH.replace("tasks/1/","tasks/9007199254740994/")));
            assertThat(page.path("taskId").asText()).isEqualTo("9007199254740994");var item=page.path("items").get(0);
            assertThat(item.path("id").asText()).isEqualTo("9007199254740995");assertThat(item.path("actor").path("id").asText()).isEqualTo("9007199254740993");
            assertThat(item.path("taskVersion").isTextual()).isTrue();assertThat(item.path("taskVersion").asText()).isEqualTo("9007199254740993");
            assertThat(Instant.parse(item.path("occurredAt").asText())).isEqualTo(Instant.parse("2026-11-10T00:00:00.123456Z"));
        }
    }
    @Test void readsDoNotMutateStateAndProjectionDoesNotGrowQueriesPerItem() throws Exception {
        var factory=alice.getBean(jakarta.persistence.EntityManagerFactory.class).unwrap(org.hibernate.SessionFactory.class);
        var stats=factory.getStatistics();String before=fingerprint();stats.clear();call(alice,PATH+"?size=1");long one=stats.getQueryExecutionCount();
        stats.clear();call(alice,PATH+"?size=100");assertThat(stats.getQueryExecutionCount()).isEqualTo(one);assertThat(one).isPositive();
        assertThat(fingerprint()).isEqualTo(before);
        String plan=db.query("EXPLAIN (FORMAT JSON) SELECT id,task_version FROM flowdesk.task_status_activities WHERE task_id=1 AND task_version<10 ORDER BY task_version DESC LIMIT 3");
        assertThat(plan).contains("Plan");assertThat(db.query("SELECT count(*) FROM pg_indexes WHERE schemaname='flowdesk' AND indexname='task_status_activities_task_version_key'")).isEqualTo("1");
    }
    @Test void defaultIs404AndReadOnlyProfileWorksButMissingReadGrantFailsClosed() throws Exception {
        try(var ctx=startFor(db,"default",Map.of())){assertThat(call(ctx,PATH).statusCode()).isEqualTo(404);}
        // Alice is a write-disabled app throughout this class.
        assertThat(call(alice,PATH).statusCode()).isEqualTo(200);
        db.sql("REVOKE SELECT(occurred_at) ON flowdesk.task_status_activities FROM flowdesk_app");
        try {
            problem(call(alice,PATH),500,"INTERNAL_ERROR");
            for(var options:List.of(actor("alice"),TaskUpdateApiTest.options("alice")))
                assertThatThrownBy(()->{try(var ignored=startFor(db,"dev-actor",options)) {}}).hasStackTraceContaining("Task-status-history-read policy");
        } finally {db.sql("GRANT SELECT(occurred_at) ON flowdesk.task_status_activities TO flowdesk_app");}
    }
    private static String fingerprint() throws Exception {
        return db.snapshot()+db.query("SELECT (SELECT json_agg(t ORDER BY id)::text FROM flowdesk.tasks t)||(SELECT json_agg(a ORDER BY id)::text FROM flowdesk.task_status_activities a)||(SELECT json_agg(s ORDER BY sequencename)::text FROM (SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='flowdesk') s)");
    }
}
