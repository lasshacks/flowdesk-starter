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
class TaskStatusHistoryAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("history_policy").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    IsolatedReadDatabase base() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();
        db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();return db;
    }
    @Test void exactColumnSelectAndAllLegacyToolsPreserveStateAndPassword() throws Exception {
        var db=base();String rows=db.snapshot(),sequences=seq(db);
        assertThat(db.grantTaskStatusHistoryRead().success()).contains("TASK_STATUS_HISTORY_READ");String acl=acl(db);
        for(var result:java.util.List.of(db.grantTaskStatusHistoryRead(),db.grantTaskStatusChange(),db.grantTaskUpdate(),db.grantTask(),db.grantProjectUpdate(),db.grantProject(),db.grantRename(),db.grantCreate(),db.grant()))
            assertThat(result.success()).contains("TASK_STATUS_HISTORY_READ");
        db.migrate("Validate").success();assertThat(db.migrate("Migrate").success()).contains("Migrations executed: 0");db.seed("Verify").success();
        assertThat(acl(db)).isEqualTo(acl);assertThat(db.snapshot()).isEqualTo(rows);assertThat(seq(db)).isEqualTo(sequences);
        assertThat(db.query("SELECT NOT has_table_privilege('flowdesk_app','flowdesk.task_status_activities','SELECT') AND (SELECT count(*)=7 FROM pg_attribute WHERE attrelid='flowdesk.task_status_activities'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'SELECT'))")).isEqualTo("t");
        try(var c=db.app();var st=c.createStatement()) {
            // All current seven columns are allowed: SELECT * is legal without a table-wide ACL.
            try(var rs=st.executeQuery("SELECT * FROM flowdesk.task_status_activities")){assertThat(rs.next()).isFalse();}
            c.setAutoCommit(false);
            st.execute("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Parent'); INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Task'); UPDATE flowdesk.tasks SET status='DONE',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=1");
            assertThat(st.executeUpdate("INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at) VALUES(1,1,'TODO','DONE',1,CURRENT_TIMESTAMP)")).isEqualTo(1);
            try(var rs=st.executeQuery("SELECT a.task_version,u.display_name FROM flowdesk.task_status_activities a JOIN flowdesk.users u ON u.id=a.actor_user_id WHERE task_id=1")) {
                assertThat(rs.next()).isTrue();assertThat(rs.getLong(1)).isEqualTo(1);assertThat(rs.getString(2)).isEqualTo("Alice");
            }
            c.rollback();
        }
        for(String sql:new String[]{"INSERT INTO flowdesk.task_status_activities(id,task_id,actor_user_id,from_status,to_status,task_version,occurred_at) OVERRIDING SYSTEM VALUE VALUES(9,1,1,'TODO','DONE',1,CURRENT_TIMESTAMP)",
            "UPDATE flowdesk.task_status_activities SET to_status='TODO' WHERE false","DELETE FROM flowdesk.task_status_activities","TRUNCATE flowdesk.task_status_activities",
            "SELECT nextval('flowdesk.task_status_activities_id_seq')","SELECT * FROM flowdesk.flyway_schema_history","CREATE TABLE flowdesk.forbidden(id int)","UPDATE flowdesk.tasks SET project_id=project_id WHERE false"})
            assertThatThrownBy(()->{try(var c=db.app();var st=c.createStatement()){st.execute(sql);}}).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).as(sql).isEqualTo("42501"));
    }
    @ParameterizedTest @ValueSource(strings={
        "GRANT SELECT(id) ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT SELECT(id,task_id,actor_user_id,from_status,to_status,task_version) ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT SELECT ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT SELECT(id,task_id,actor_user_id,from_status,to_status,task_version,occurred_at) ON flowdesk.task_status_activities TO PUBLIC",
        "GRANT SELECT(id) ON flowdesk.task_status_activities TO flowdesk_app WITH GRANT OPTION",
        "GRANT SELECT,UPDATE ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT SELECT(id) ON flowdesk.task_status_activities TO PUBLIC",
        "GRANT UPDATE(to_status) ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT INSERT(id) ON flowdesk.task_status_activities TO flowdesk_app",
        "GRANT USAGE ON SEQUENCE flowdesk.task_status_activities_id_seq TO flowdesk_app"})
    void partialExcessPublicOrGrantOptionIsNotRepaired(String sql) throws Exception {
        var db=base();db.sql(sql);String before=acl(db);
        db.grantTaskStatusHistoryRead().failure();db.grantTaskStatusChange().failure();db.grant().failure();db.migrate("Validate").failure();db.seed("Verify").failure();
        assertThat(acl(db)).isEqualTo(before);
    }
    @Test void futureColumnIsNotAutomaticallyReadableOrGrantable() throws Exception {
        var db=base();db.grantTaskStatusHistoryRead().success();db.sql("ALTER TABLE flowdesk.task_status_activities ADD COLUMN admin_note text");
        try(var c=db.app();var st=c.createStatement();var rs=st.executeQuery("SELECT id,task_id,actor_user_id,from_status,to_status,task_version,occurred_at FROM flowdesk.task_status_activities")){assertThat(rs.next()).isFalse();}
        assertThatThrownBy(()->{try(var c=db.app();var st=c.createStatement()){st.executeQuery("SELECT * FROM flowdesk.task_status_activities");}})
                .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
        db.sql("GRANT SELECT(admin_note) ON flowdesk.task_status_activities TO flowdesk_app");String before=acl(db);
        db.grantTaskStatusHistoryRead().failure();db.grantTaskStatusChange().failure();assertThat(acl(db)).isEqualTo(before);
    }
    @Test void requiresPreviousStateCredentialAndCurrentChecksum() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantTaskStatusHistoryRead().failure();
        db=base();String before=acl(db);
        db.grantTaskStatusHistoryRead(Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64))).failure();assertThat(acl(db)).isEqualTo(before);
        db.sql("UPDATE flowdesk.flyway_schema_history SET checksum=1 WHERE version='7'");db.grantTaskStatusHistoryRead().failure();assertThat(acl(db)).isEqualTo(before);
    }
    private String acl(IsolatedReadDatabase db) throws Exception {
        return db.query("SELECT (SELECT json_agg(x ORDER BY relname)::text FROM (SELECT relname,relacl::text FROM pg_class WHERE relnamespace='flowdesk'::regnamespace) x)||coalesce((SELECT json_agg(x ORDER BY attrelid,attnum)::text FROM (SELECT attrelid,attnum,attacl::text FROM pg_attribute WHERE attrelid IN (SELECT oid FROM pg_class WHERE relnamespace='flowdesk'::regnamespace) AND attacl IS NOT NULL) x),'[]')");
    }
    private String seq(IsolatedReadDatabase db)throws Exception{return db.query("SELECT json_agg(x ORDER BY sequencename)::text FROM (SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='flowdesk') x");}
}
