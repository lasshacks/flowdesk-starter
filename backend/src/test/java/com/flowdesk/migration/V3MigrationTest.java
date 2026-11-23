package com.flowdesk.migration;
import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class V3MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v3_test").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    @Test void upgradesPopulatedV2PreservingRowsAclHistoryAndSequences()throws Exception{
        var db=new IsolatedReadDatabase(POSTGRES,false,"2");
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('v2@example.test','V2'); INSERT INTO flowdesk.workspaces(name,version) VALUES('Existing',7); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER')");
        db.sql("GRANT USAGE ON SCHEMA flowdesk TO flowdesk_app; GRANT SELECT ON flowdesk.users,flowdesk.workspaces,flowdesk.workspace_memberships TO flowdesk_app; GRANT INSERT ON flowdesk.workspaces,flowdesk.workspace_memberships TO flowdesk_app; GRANT UPDATE(name,updated_at,version) ON flowdesk.workspaces TO flowdesk_app");
        String before=db.snapshot();String seq=sequences(db);
        String history=db.query("SELECT json_agg(h ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history h WHERE version IN ('1','2')");
        String acl=db.query("SELECT string_agg(coalesce(relacl::text,''),',' ORDER BY relname) FROM pg_class WHERE relnamespace='flowdesk'::regnamespace");
        assertThat(db.migrateTo("3")).isEqualTo(1);
        String after=db.snapshot();assertThat(after.substring(0,after.lastIndexOf("[{\"installed_rank\""))).isEqualTo(before.substring(0,before.lastIndexOf("[{\"installed_rank\"")));
        assertThat(sequences(db)).isEqualTo(seq);
        assertThat(db.query("SELECT json_agg(h ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history h WHERE version IN ('1','2')")).isEqualTo(history);
        assertThat(db.query("SELECT count(*) FROM flowdesk.projects")).isEqualTo("0");
        assertThat(db.query("SELECT count(*) FROM pg_attribute WHERE attrelid='flowdesk.workspaces'::regclass AND attacl IS NOT NULL AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE')")).isEqualTo("3");
        assertThat(db.query("SELECT count(*) FROM pg_indexes WHERE schemaname='flowdesk' AND tablename='projects' AND indexdef LIKE '%(workspace_id, id)%'")).isEqualTo("1");
        assertThat(db.query("SELECT count(*) FROM pg_constraint WHERE conrelid='flowdesk.projects'::regclass AND contype='f' AND confrelid='flowdesk.workspaces'::regclass AND confdeltype='r'")).isEqualTo("1");
        assertThat(db.query("SELECT string_agg(coalesce(relacl::text,''),',' ORDER BY relname) FROM pg_class WHERE relnamespace='flowdesk'::regnamespace AND relname NOT LIKE 'projects%'")).isEqualTo(acl);
        db.validateTo("3");assertThat(db.migrateTo("3")).isZero();
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Same'),(1,'Same')");
        for(String sql:new String[]{"INSERT INTO flowdesk.projects(workspace_id,name) VALUES(999,'Bad')","DELETE FROM flowdesk.workspaces WHERE id=1","INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,' ')","INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,repeat('x',101))","INSERT INTO flowdesk.projects(workspace_id,name,description) VALUES(1,'x',repeat('x',2001))"}){
            assertThatThrownBy(()->db.sql(sql)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState().substring(0,2)).isIn("23","22"));
        }
    }
    private String sequences(IsolatedReadDatabase db)throws Exception{
        return db.query("SELECT (SELECT row_to_json(s)::text FROM (SELECT last_value,is_called FROM flowdesk.users_id_seq) s)||(SELECT row_to_json(s)::text FROM (SELECT last_value,is_called FROM flowdesk.workspaces_id_seq) s)||(SELECT row_to_json(s)::text FROM (SELECT last_value,is_called FROM flowdesk.workspace_memberships_id_seq) s)");
    }
}
