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
class TaskUpdateAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("task_update_policy").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    IsolatedReadDatabase base() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();return db;
    }
    @Test void exactColumnsAndLegacyToolsPreserveStateAndCredentials() throws Exception {
        var db=base();String before=db.snapshot();
        assertThat(db.grantTaskUpdate().success()).contains("TASK_UPDATE");
        String acl=acl(db);
        assertThat(db.grantTaskUpdate().success()).contains("TASK_UPDATE");
        assertThat(db.grantTask().success()).contains("TASK_UPDATE");
        assertThat(db.grantRename().success()).contains("TASK_UPDATE");
        assertThat(db.grantCreate().success()).contains("TASK_UPDATE");
        assertThat(db.grant().success()).contains("TASK_UPDATE");
        assertThat(db.grantProjectUpdate().success()).contains("TASK_UPDATE");
        assertThat(db.grantProject().success()).contains("TASK_UPDATE");
        db.migrate("Validate").success();assertThat(db.migrate("Migrate").success()).contains("Migrations executed: 0");db.seed("Verify").success();
        assertThat(acl(db)).isEqualTo(acl);assertThat(db.snapshot()).isEqualTo(before);
        assertThat(db.query("SELECT string_agg(attname,',' ORDER BY attname) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE')")).isEqualTo("description,priority,title,updated_at,version");
        try(var c=db.app();var st=c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Parent'); INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Probe')");
            assertThat(st.executeUpdate("UPDATE flowdesk.tasks SET title='Changed',description='Text',priority='HIGH',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE version=0")).isEqualTo(1);
            c.rollback();
        }
        for(String sql:new String[]{"UPDATE flowdesk.tasks SET project_id=project_id WHERE false","UPDATE flowdesk.tasks SET id=DEFAULT WHERE false","UPDATE flowdesk.tasks SET status=status WHERE false","UPDATE flowdesk.tasks SET created_at=created_at WHERE false","DELETE FROM flowdesk.tasks","TRUNCATE flowdesk.tasks","SELECT nextval('flowdesk.tasks_id_seq')","SELECT * FROM flowdesk.flyway_schema_history","CREATE TABLE flowdesk.forbidden(id int)"})
            assertThatThrownBy(()->{try(var c=db.app();var st=c.createStatement()){st.execute(sql);}}).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
    }
    @ParameterizedTest @ValueSource(strings={
        "GRANT UPDATE(title) ON flowdesk.tasks TO flowdesk_app",
        "GRANT UPDATE(title,description,updated_at) ON flowdesk.tasks TO flowdesk_app",
        "GRANT UPDATE(title,description,priority,updated_at,version,project_id) ON flowdesk.tasks TO flowdesk_app",
        "GRANT UPDATE(title,description,priority,updated_at,version,status) ON flowdesk.tasks TO flowdesk_app",
        "GRANT UPDATE(title,description,priority,updated_at,version,created_at) ON flowdesk.tasks TO flowdesk_app",
        "GRANT UPDATE ON flowdesk.tasks TO flowdesk_app",
        "GRANT UPDATE(title,description,priority,updated_at,version) ON flowdesk.tasks TO PUBLIC",
        "GRANT UPDATE(title,description,priority,updated_at,version) ON flowdesk.tasks TO flowdesk_app WITH GRANT OPTION"})
    void partialExcessPublicOrGrantOptionCannotBeRepaired(String sql) throws Exception {
        var db=base();db.sql(sql);String before=acl(db);
        db.grantTaskUpdate().failure();db.grantTask().failure();db.migrate("Validate").failure();db.seed("Verify").failure();
        assertThat(acl(db)).isEqualTo(before);
    }
    @Test void requiresTaskCreateAndCorrectPasswordAndCurrentHistory() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantTaskUpdate().failure();
        db.grantCreate().success();db.grantRename().success();db.grantTaskUpdate().failure();
        db.grantProject().success();db.grantProjectUpdate().success();db.grantTask().success();
        String before=acl(db);db.grantTaskUpdate(Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64))).failure();
        assertThat(acl(db)).isEqualTo(before);
        db.sql("UPDATE flowdesk.flyway_schema_history SET checksum=1 WHERE version='4'");db.grantTaskUpdate().failure();assertThat(acl(db)).isEqualTo(before);
    }
    private String acl(IsolatedReadDatabase db) throws Exception {
        return db.query("SELECT coalesce((SELECT relacl::text FROM pg_class WHERE oid='flowdesk.tasks'::regclass),'')||coalesce((SELECT string_agg(attname||coalesce(attacl::text,''),',' ORDER BY attnum) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped),'')");
    }
}
