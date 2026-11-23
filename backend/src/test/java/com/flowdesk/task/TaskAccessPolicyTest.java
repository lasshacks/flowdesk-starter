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
class TaskAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("task_policy").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    IsolatedReadDatabase base()throws Exception{
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();return db;
    }
    @Test void exactGrantsIdentityInsertAndAllOldToolsPreserve()throws Exception{
        var db=base();String before=db.snapshot();assertThat(db.grantTask().success()).contains("TASK_CREATE");String acl=acl(db);
        for(var result:new IsolatedReadDatabase.Result[]{db.grantTask(),db.grantProjectUpdate(),db.grantProject(),db.grantRename(),db.grantCreate(),db.grant()})assertThat(result.success()).contains("TASK_CREATE");
        db.migrate("Validate").success();assertThat(db.migrate("Migrate").success()).contains("Migrations executed: 0");db.seed("Verify").success();
        assertThat(acl(db)).isEqualTo(acl);assertThat(db.snapshot()).isEqualTo(before);
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Parent')");
        try(var c=db.app();var st=c.createStatement()){
            c.setAutoCommit(false);try(var rs=st.executeQuery("INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Task') RETURNING id,status,priority")){
                assertThat(rs.next()).isTrue();assertThat(rs.getLong(1)).isPositive();assertThat(rs.getString(2)).isEqualTo("TODO");assertThat(rs.getString(3)).isEqualTo("MEDIUM");
            }c.rollback();
        }
        for(String sql:new String[]{"UPDATE flowdesk.tasks SET title='No'","DELETE FROM flowdesk.tasks","TRUNCATE flowdesk.tasks","SELECT nextval('flowdesk.tasks_id_seq')","SELECT * FROM flowdesk.flyway_schema_history","CREATE TABLE flowdesk.forbidden(id int)"})
            assertThatThrownBy(()->{try(var c=db.app();var st=c.createStatement()){st.execute(sql);}}).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
        db.sql("INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Business')");String tasks=db.query("SELECT json_agg(t)::text FROM flowdesk.tasks t");
        assertThat(db.seed("Apply").failure()).contains("mismatch");assertThat(db.seed("Verify").failure()).contains("mismatch");assertThat(db.query("SELECT json_agg(t)::text FROM flowdesk.tasks t")).isEqualTo(tasks);
    }
    @ParameterizedTest @ValueSource(strings={"GRANT SELECT ON flowdesk.tasks TO flowdesk_app","GRANT INSERT ON flowdesk.tasks TO flowdesk_app",
            "GRANT SELECT,INSERT,UPDATE ON flowdesk.tasks TO flowdesk_app","GRANT SELECT,INSERT ON flowdesk.tasks TO PUBLIC",
            "GRANT SELECT,INSERT ON flowdesk.tasks TO flowdesk_app WITH GRANT OPTION","GRANT UPDATE(title) ON flowdesk.tasks TO flowdesk_app",
            "GRANT USAGE ON SEQUENCE flowdesk.tasks_id_seq TO flowdesk_app"})
    void partialExcessPublicOrGrantOptionCannotBeRepaired(String sql)throws Exception{
        var db=base();db.sql(sql);String before=acl(db);
        db.grantTask().failure();db.grantProjectUpdate().failure();db.migrate("Validate").failure();db.seed("Verify").failure();assertThat(acl(db)).isEqualTo(before);
    }
    @Test void prerequisiteCredentialAndHistoryGuards()throws Exception{
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantTask().failure();db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantTask().failure();db.grantProjectUpdate().success();
        String before=acl(db);db.grantTask(Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64))).failure();assertThat(acl(db)).isEqualTo(before);
        db.sql("UPDATE flowdesk.flyway_schema_history SET checksum=1 WHERE version='5'");db.grantTask().failure();assertThat(acl(db)).isEqualTo(before);
    }
    private String acl(IsolatedReadDatabase db)throws Exception{return db.query("SELECT coalesce((SELECT relacl::text FROM pg_class WHERE oid='flowdesk.tasks'::regclass),'')||coalesce((SELECT string_agg(attname||coalesce(attacl::text,''),',' ORDER BY attnum) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped),'')||coalesce((SELECT relacl::text FROM pg_class WHERE oid='flowdesk.tasks_id_seq'::regclass),'')");}
}
