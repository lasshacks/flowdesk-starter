package com.flowdesk.task;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TaskStatusAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("task_status_policy").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    IsolatedReadDatabase base() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();
        db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();db.grantTaskUpdate().success();return db;
    }
    @Test void exactColumnInsertNeedsNeitherSelectGeneratedKeysNorSequence() throws Exception {
        var db=base();String before=db.snapshot();
        assertThat(db.grantTaskStatusChange().success()).contains("TASK_STATUS_CHANGE");String acl=acl(db);
        for(var result:java.util.List.of(db.grantTaskStatusChange(),db.grantTaskUpdate(),db.grantTask(),db.grantProjectUpdate(),db.grantProject(),db.grantRename(),db.grantCreate(),db.grant()))
            assertThat(result.success()).contains("TASK_STATUS_CHANGE");
        db.migrate("Validate").success();assertThat(db.migrate("Migrate").success()).contains("Migrations executed: 0");db.seed("Verify").success();
        assertThat(acl(db)).isEqualTo(acl);assertThat(db.snapshot()).isEqualTo(before);
        try(var c=db.app();var st=c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Parent'); INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Task')");
            assertThat(st.executeUpdate("UPDATE flowdesk.tasks SET status='DONE',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=1 AND version=0")).isEqualTo(1);
            assertThat(st.executeUpdate("INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at) VALUES(1,1,'TODO','DONE',1,CURRENT_TIMESTAMP)")).isEqualTo(1);
            c.rollback();
        }
        assertThat(db.query("SELECT count(*) FROM flowdesk.task_status_activities")).isEqualTo("0");
        for(String sql:new String[]{"SELECT * FROM flowdesk.task_status_activities","SELECT task_id FROM flowdesk.task_status_activities",
                "INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at) VALUES(1,1,'TODO','DONE',1,CURRENT_TIMESTAMP) RETURNING id",
                "INSERT INTO flowdesk.task_status_activities(id,task_id,actor_user_id,from_status,to_status,task_version,occurred_at) OVERRIDING SYSTEM VALUE VALUES(9,1,1,'TODO','DONE',1,CURRENT_TIMESTAMP)",
                "UPDATE flowdesk.task_status_activities SET to_status='TODO' WHERE false","DELETE FROM flowdesk.task_status_activities","TRUNCATE flowdesk.task_status_activities",
                "SELECT nextval('flowdesk.task_status_activities_id_seq')","UPDATE flowdesk.tasks SET project_id=project_id WHERE false",
                "UPDATE flowdesk.tasks SET id=DEFAULT WHERE false","UPDATE flowdesk.tasks SET created_at=created_at WHERE false","UPDATE flowdesk.tasks SET (project_id,title,description,status,priority,created_at,updated_at,version)=(project_id,title,description,status,priority,created_at,updated_at,version) WHERE false",
                "SELECT * FROM flowdesk.flyway_schema_history","CREATE TABLE flowdesk.forbidden(id int)"})
            assertThatThrownBy(()->{try(var c=db.app();var st=c.createStatement()){st.execute(sql);}})
                    .as(sql).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).as(sql).isEqualTo("42501"));
    }
    @ParameterizedTest @ValueSource(strings={
        "GRANT UPDATE(status) ON flowdesk.tasks TO flowdesk_app",
        "GRANT INSERT(task_id) ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT INSERT(task_id,actor_user_id,from_status,to_status,task_version,occurred_at) ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT SELECT ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT INSERT ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT INSERT(id) ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT INSERT(task_id) ON flowdesk.task_status_activities TO PUBLIC",
        "GRANT INSERT(task_id) ON flowdesk.task_status_activities TO flowdesk_app WITH GRANT OPTION",
        "GRANT SELECT(task_id) ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT UPDATE(status) ON flowdesk.tasks TO PUBLIC",
        "GRANT USAGE ON SEQUENCE flowdesk.task_status_activities_id_seq TO flowdesk_app"})
    void partialExcessPublicAndGrantOptionAreNotRepaired(String sql) throws Exception {
        var db=base();db.sql(sql);String before=acl(db);
        db.grantTaskStatusChange().failure();db.grantTaskUpdate().failure();db.grant().failure();db.migrate("Validate").failure();db.seed("Verify").failure();
        assertThat(acl(db)).isEqualTo(before);
    }
    @Test void requiresPreviousPolicyCredentialAndExactHistory() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantTaskStatusChange().failure();
        db=base();String before=acl(db);
        db.grantTaskStatusChange(Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64))).failure();assertThat(acl(db)).isEqualTo(before);
        db.sql("UPDATE flowdesk.flyway_schema_history SET checksum=1 WHERE version='7'");db.grantTaskStatusChange().failure();assertThat(acl(db)).isEqualTo(before);
    }
    @Test void secondGrantFailureRollsBackTheFirstGrant() throws Exception {
        var db=base();db.sql("ALTER TABLE flowdesk.task_status_activities DROP COLUMN occurred_at");String before=acl(db);
        db.grantTaskStatusChange().failure();
        assertThat(acl(db)).isEqualTo(before);
        assertThat(db.query("SELECT NOT has_column_privilege('flowdesk_app','flowdesk.tasks','status','UPDATE')")).isEqualTo("t");
    }
    private String acl(IsolatedReadDatabase db) throws Exception {
        return db.query("SELECT (SELECT json_agg(x ORDER BY relname)::text FROM (SELECT relname,relacl::text FROM pg_class WHERE relnamespace='flowdesk'::regnamespace) x)||coalesce((SELECT json_agg(x ORDER BY attrelid,attnum)::text FROM (SELECT attrelid,attnum,attacl::text FROM pg_attribute WHERE attrelid IN (SELECT oid FROM pg_class WHERE relnamespace='flowdesk'::regnamespace) AND attacl IS NOT NULL) x),'[]')");
    }
}
