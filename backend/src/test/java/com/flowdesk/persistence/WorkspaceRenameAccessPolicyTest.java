package com.flowdesk.persistence;

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
class WorkspaceRenameAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("rename_policy").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    IsolatedReadDatabase fresh() throws Exception {
        var db = new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();return db;
    }
    String acl(IsolatedReadDatabase db) throws Exception {
        return db.query("SELECT json_agg(x ORDER BY relname,attname)::text FROM (SELECT c.relname,a.attname,a.attacl::text FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND a.attnum>0) x");
    }
    @Test void exactColumnsRepeatAndAllToolsPreserveStateAndPassword() throws Exception {
        var db=fresh();String before=db.snapshot();
        assertThat(db.grantRename().success()).contains("WORKSPACE_RENAME");String acl=acl(db);
        db.grantRename().success();assertThat(db.grantCreate().success()).contains("WORKSPACE_RENAME");
        assertThat(db.grant().success()).contains("WORKSPACE_RENAME");db.migrate("Info").success();db.migrate("Validate").success();
        assertThat(db.migrate("Migrate").success()).contains("Migrations executed: 0");
        db.seed("Verify").success();assertThat(db.seed("Apply").success()).contains("Seed rows inserted: 0");
        try(var c=db.app()) {
            c.setAutoCommit(false);
            assertThat(c.createStatement().executeUpdate("UPDATE flowdesk.workspaces SET name='Probe',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=(SELECT min(id) FROM flowdesk.workspaces) AND version=0")).isEqualTo(1);
            c.rollback();
        }
        assertThat(db.snapshot()).isEqualTo(before);assertThat(acl(db)).isEqualTo(acl);
        for(String sql:new String[]{"UPDATE flowdesk.workspaces SET id=DEFAULT WHERE false", "UPDATE flowdesk.workspaces SET created_at=created_at WHERE false",
                "UPDATE flowdesk.users SET display_name='x' WHERE false", "UPDATE flowdesk.workspace_memberships SET role='OWNER' WHERE false",
                "DELETE FROM flowdesk.workspaces WHERE false", "SELECT * FROM flowdesk.flyway_schema_history", "SELECT nextval('flowdesk.workspaces_id_seq')", "CREATE TABLE flowdesk.forbidden(id int)"}) {
            try(var c=db.app();var st=c.createStatement()) {
                assertThatThrownBy(()->st.execute(sql)).as(sql).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={
        "GRANT UPDATE(name) ON flowdesk.workspaces TO flowdesk_app",
        "GRANT UPDATE(name,updated_at) ON flowdesk.workspaces TO flowdesk_app",
        "GRANT UPDATE(name,updated_at,version,created_at) ON flowdesk.workspaces TO flowdesk_app",
        "GRANT UPDATE(name,updated_at,version) ON flowdesk.workspaces TO flowdesk_app WITH GRANT OPTION",
        "GRANT UPDATE(name,updated_at,version) ON flowdesk.workspaces TO PUBLIC",
        "GRANT UPDATE ON flowdesk.workspaces TO flowdesk_app",
        "GRANT UPDATE(role) ON flowdesk.workspace_memberships TO flowdesk_app",
        "GRANT UPDATE(display_name) ON flowdesk.users TO flowdesk_app"})
    void partialExcessiveOrPublicColumnsBlockEveryToolWithoutRepair(String change) throws Exception {
        var db=fresh();db.sql(change);String before=db.snapshot(),policy=acl(db);
        db.grantRename().failure();db.grantCreate().failure();db.grant().failure();db.migrate("Validate").failure();db.seed("Verify").failure();
        assertThat(db.snapshot()).isEqualTo(before);assertThat(acl(db)).isEqualTo(policy);
    }
    @Test void rejectsReadOnlyV1WrongCredentialAndBadHistoryWithoutGrants() throws Exception {
        var read=new IsolatedReadDatabase(POSTGRES,true);read.grantRename().failure();
        var v1=new IsolatedReadDatabase(POSTGRES,false,"1");v1.grantRename().failure();
        var db=fresh();String policy=acl(db);
        db.grantRename(Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64))).failure();
        db.sql("UPDATE flowdesk.flyway_schema_history SET checksum=1 WHERE version='2'");db.grantRename().failure();
        assertThat(acl(db)).isEqualTo(policy);
    }
}
