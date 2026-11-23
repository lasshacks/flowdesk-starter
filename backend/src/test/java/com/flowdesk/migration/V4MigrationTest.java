package com.flowdesk.migration;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class V4MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v4_test").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    @Test void realRunnerUpgradesPopulatedV3PreservingDataHistoryAclAndSequences() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,false,"3");
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('before@example.test','Before'); INSERT INTO flowdesk.workspaces(name,version) VALUES('Parent',7); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER'); INSERT INTO flowdesk.projects(workspace_id,name,description) VALUES(1,'Existing','Before V4')");
        db.sql("GRANT USAGE ON SCHEMA flowdesk TO flowdesk_app; GRANT SELECT ON flowdesk.users,flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.projects TO flowdesk_app; GRANT INSERT ON flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.projects TO flowdesk_app; GRANT UPDATE(name,updated_at,version) ON flowdesk.workspaces TO flowdesk_app");
        String rows=data(db),history=history(db),acl=acl(db),sequences=sequences(db);
        assertThat(db.migrateTo("4")).isEqualTo(1);
        assertThat(data(db)).isEqualTo(rows);assertThat(history(db)).isEqualTo(history);assertThat(acl(db)).isEqualTo(acl);assertThat(sequences(db)).isEqualTo(sequences);
        assertThat(db.query("SELECT version FROM flowdesk.projects")).isEqualTo("0");
        assertThat(db.query("SELECT string_agg(version,',' ORDER BY installed_rank) FROM flowdesk.flyway_schema_history WHERE success")).isEqualTo("1,2,3,4");
        db.validateTo("4");assertThat(db.migrateTo("4")).isZero();
        assertThatThrownBy(()->db.sql("UPDATE flowdesk.projects SET version=-1")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(()->db.sql("UPDATE flowdesk.projects SET version=NULL")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23502"));
    }
    private String data(IsolatedReadDatabase db) throws Exception {
        return db.query("SELECT (SELECT json_agg(x ORDER BY id)::text FROM flowdesk.users x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspaces x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspace_memberships x)||(SELECT json_agg(x ORDER BY id)::text FROM (SELECT id,workspace_id,name,description,created_at,updated_at FROM flowdesk.projects) x)");
    }
    private String history(IsolatedReadDatabase db) throws Exception {return db.query("SELECT json_agg(x ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history x WHERE version IN ('1','2','3')");}
    private String acl(IsolatedReadDatabase db) throws Exception {return db.query("SELECT (SELECT json_agg(x ORDER BY relname)::text FROM (SELECT relname,relacl::text,pg_get_userbyid(relowner) FROM pg_class WHERE relnamespace='flowdesk'::regnamespace) x)||(SELECT json_agg(x ORDER BY attrelid,attnum)::text FROM (SELECT attrelid,attnum,attacl::text FROM pg_attribute WHERE attrelid IN (SELECT oid FROM pg_class WHERE relnamespace='flowdesk'::regnamespace) AND attacl IS NOT NULL) x)");}
    private String sequences(IsolatedReadDatabase db) throws Exception {return db.query("SELECT json_agg(x ORDER BY sequencename)::text FROM (SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='flowdesk') x");}
}
