package com.flowdesk.migration;
import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class V5MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v5_test").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    @Test void upgradesPopulatedV4WithoutChangingExistingDataOrPermissions()throws Exception{
        var db=new IsolatedReadDatabase(POSTGRES,false,"4");
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('before@example.test','Before'); INSERT INTO flowdesk.workspaces(name,version) VALUES('Parent',7); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER'); INSERT INTO flowdesk.projects(workspace_id,name,description,version) VALUES(1,'Existing','Before V5',9)");
        db.sql("GRANT USAGE ON SCHEMA flowdesk TO flowdesk_app; GRANT SELECT ON flowdesk.users,flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.projects TO flowdesk_app; GRANT INSERT ON flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.projects TO flowdesk_app; GRANT UPDATE(name,updated_at,version) ON flowdesk.workspaces TO flowdesk_app; GRANT UPDATE(name,description,updated_at,version) ON flowdesk.projects TO flowdesk_app");
        String rows=data(db),history=history(db),acl=acl(db),sequences=sequences(db);
        assertThat(db.migrateTo("5")).isEqualTo(1);db.validateTo("5");assertThat(db.migrateTo("5")).isZero();
        assertThat(data(db)).isEqualTo(rows);assertThat(history(db)).isEqualTo(history);assertThat(acl(db)).isEqualTo(acl);assertThat(sequences(db)).isEqualTo(sequences);
        assertThat(db.query("SELECT string_agg(version,',' ORDER BY installed_rank) FROM flowdesk.flyway_schema_history WHERE success")).isEqualTo("1,2,3,4,5");
        assertThat(db.query("SELECT count(*) FROM flowdesk.tasks")).isEqualTo("0");
        assertThat(db.query("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='flowdesk.tasks'::regclass")).isEqualTo("flowdesk_migrator");
        assertThat(db.query("SELECT NOT has_table_privilege('flowdesk_app','flowdesk.tasks','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')")).isEqualTo("t");
        assertThat(db.query("SELECT count(*) FROM pg_indexes WHERE schemaname='flowdesk' AND indexname='tasks_project_id_id_idx'")).isEqualTo("1");
        String[] bad={"INSERT INTO flowdesk.tasks(project_id,title) VALUES(999,'No')","INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,NULL)","INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,' ')","INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,repeat('x',201))","INSERT INTO flowdesk.tasks(project_id,title,description) VALUES(1,'x',repeat('x',2001))","INSERT INTO flowdesk.tasks(project_id,title,status) VALUES(1,'x','UNKNOWN')","INSERT INTO flowdesk.tasks(project_id,title,priority) VALUES(1,'x','URGENT')","INSERT INTO flowdesk.tasks(project_id,title,priority) VALUES(1,'x',NULL)"};
        for(String sql:bad)assertThatThrownBy(()->db.sql(sql)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).startsWith("2"));
        db.sql("INSERT INTO flowdesk.tasks(project_id,title) VALUES(1,'Same'),(1,'Same')");
        assertThat(db.query("SELECT count(*)=2 AND bool_and(status='TODO' AND priority='MEDIUM' AND created_at=updated_at) FROM flowdesk.tasks")).isEqualTo("t");
        assertThatThrownBy(()->db.sql("DELETE FROM flowdesk.projects WHERE id=1")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23503"));
    }
    private String data(IsolatedReadDatabase db)throws Exception{return db.query("SELECT (SELECT json_agg(x ORDER BY id)::text FROM flowdesk.users x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspaces x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspace_memberships x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.projects x)");}
    private String history(IsolatedReadDatabase db)throws Exception{return db.query("SELECT json_agg(x ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history x WHERE version IN ('1','2','3','4')");}
    private String acl(IsolatedReadDatabase db)throws Exception{return db.query("SELECT (SELECT json_agg(x ORDER BY relname)::text FROM (SELECT relname,relacl::text,pg_get_userbyid(relowner) FROM pg_class WHERE relnamespace='flowdesk'::regnamespace AND relname NOT LIKE 'tasks%') x)||(SELECT json_agg(x ORDER BY attrelid,attnum)::text FROM (SELECT attrelid,attnum,attacl::text FROM pg_attribute WHERE attrelid IN (SELECT oid FROM pg_class WHERE relnamespace='flowdesk'::regnamespace AND relname NOT LIKE 'tasks%') AND attacl IS NOT NULL) x)");}
    private String sequences(IsolatedReadDatabase db)throws Exception{return db.query("SELECT json_agg(x ORDER BY sequencename)::text FROM (SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='flowdesk' AND sequencename<>'tasks_id_seq') x");}
}
