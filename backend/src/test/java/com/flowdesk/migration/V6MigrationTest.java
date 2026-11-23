package com.flowdesk.migration;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class V6MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v6_test").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    @Test void populatedV5UpgradePreservesAllPreviousDataAndAccess() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,false,"5");
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('before@example.test','Before'); INSERT INTO flowdesk.workspaces(name,version) VALUES('Parent',7); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER'); INSERT INTO flowdesk.projects(workspace_id,name,description,version) VALUES(1,'Existing','Before V6',9); INSERT INTO flowdesk.tasks(project_id,title,description,status,priority) VALUES(1,'Existing Task','Before V6','IN_PROGRESS','HIGH')");
        db.sql("GRANT USAGE ON SCHEMA flowdesk TO flowdesk_app; GRANT SELECT ON flowdesk.users,flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.projects,flowdesk.tasks TO flowdesk_app; GRANT INSERT ON flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.projects,flowdesk.tasks TO flowdesk_app; GRANT UPDATE(name,updated_at,version) ON flowdesk.workspaces TO flowdesk_app; GRANT UPDATE(name,description,updated_at,version) ON flowdesk.projects TO flowdesk_app");
        String rows=data(db),history=history(db),acl=acl(db),seq=sequences(db);
        assertThat(db.migrateTo("6")).isEqualTo(1);db.validateTo("6");
        assertThat(db.migrateTo("6")).isZero();
        assertThat(data(db)).isEqualTo(rows);assertThat(history(db)).isEqualTo(history);assertThat(acl(db)).isEqualTo(acl);assertThat(sequences(db)).isEqualTo(seq);
        assertThat(db.query("SELECT string_agg(version,',' ORDER BY installed_rank) FROM flowdesk.flyway_schema_history WHERE success")).isEqualTo("1,2,3,4,5,6");
        assertThat(db.query("SELECT version FROM flowdesk.tasks WHERE id=1")).isEqualTo("0");
        for(String sql:new String[]{"UPDATE flowdesk.tasks SET version=-1","UPDATE flowdesk.tasks SET version=NULL"})
            assertThatThrownBy(()->db.sql(sql)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).startsWith("23"));
        db.sql("GRANT SELECT,INSERT ON flowdesk.tasks TO flowdesk_app");
        assertThat(data(db)).isEqualTo(rows);assertThat(db.query("SELECT NOT has_any_column_privilege('flowdesk_app','flowdesk.tasks','UPDATE')")).isEqualTo("t");
    }
    private String data(IsolatedReadDatabase db)throws Exception{return db.query("SELECT (SELECT json_agg(x ORDER BY id)::text FROM flowdesk.users x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspaces x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspace_memberships x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.projects x)||(SELECT jsonb_agg(to_jsonb(x)-'version' ORDER BY id)::text FROM flowdesk.tasks x)");}
    private String history(IsolatedReadDatabase db)throws Exception{return db.query("SELECT json_agg(x ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history x WHERE version IN ('1','2','3','4','5')");}
    private String acl(IsolatedReadDatabase db)throws Exception{return db.query("SELECT (SELECT json_agg(x ORDER BY relname)::text FROM (SELECT relname,relacl::text,pg_get_userbyid(relowner) FROM pg_class WHERE relnamespace='flowdesk'::regnamespace) x)||(SELECT json_agg(x ORDER BY attrelid,attnum)::text FROM (SELECT attrelid,attnum,attacl::text FROM pg_attribute WHERE attrelid IN (SELECT oid FROM pg_class WHERE relnamespace='flowdesk'::regnamespace) AND attacl IS NOT NULL) x)");}
    private String sequences(IsolatedReadDatabase db)throws Exception{return db.query("SELECT json_agg(x ORDER BY sequencename)::text FROM (SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='flowdesk') x");}
}
