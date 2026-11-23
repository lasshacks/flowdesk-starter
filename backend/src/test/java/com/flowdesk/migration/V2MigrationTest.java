package com.flowdesk.migration;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class V2MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v2_test").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test void upgradesPopulatedV1ToV2WithoutChangingItsRowsOrChecksumAndRepeats() throws Exception {
        var db = new IsolatedReadDatabase(POSTGRES,false,"1");
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('v1@example.test','V1'); INSERT INTO flowdesk.workspaces(name) VALUES('Existing'); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER')");
        String before = data(db), checksum = db.query("SELECT checksum FROM flowdesk.flyway_schema_history WHERE version='1'");
        assertThat(db.migrateTo("2")).isEqualTo(1);
        assertThat(data(db)).isEqualTo(before);
        assertThat(db.query("SELECT version FROM flowdesk.workspaces")).isEqualTo("0");
        assertThat(db.query("SELECT checksum FROM flowdesk.flyway_schema_history WHERE version='1'")).isEqualTo(checksum);
        assertThat(db.query("SELECT string_agg(version,',' ORDER BY installed_rank) FROM flowdesk.flyway_schema_history WHERE success")).isEqualTo("1,2");
        db.validateTo("2");assertThat(db.migrateTo("2")).isZero();
        assertThatThrownBy(()->db.sql("UPDATE flowdesk.workspaces SET version=-1")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(()->db.sql("UPDATE flowdesk.workspaces SET version=NULL")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23502"));
    }
    private String data(IsolatedReadDatabase db) throws Exception {
        return db.query("SELECT (SELECT json_agg(u ORDER BY id)::text FROM flowdesk.users u)||(SELECT json_agg(m ORDER BY id)::text FROM flowdesk.workspace_memberships m)||(SELECT json_agg(x ORDER BY id)::text FROM (SELECT id,name,created_at,updated_at FROM flowdesk.workspaces) x)");
    }
}
