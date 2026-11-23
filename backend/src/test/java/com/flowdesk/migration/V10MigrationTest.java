package com.flowdesk.migration;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class V10MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v10_migration").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test void addsNullableArchiveTimestampWithoutChangingExistingProjectRows() throws Exception {
        var db = new IsolatedReadDatabase(POSTGRES, false, "9");
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('archive-test@example.test','Archive test'); INSERT INTO flowdesk.workspaces(name) VALUES('Archive workspace'); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER')");
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name,description) VALUES(1,'Keep me','Existing project')");
        assertThat(db.migrateTo("10")).isEqualTo(1);
        assertThat(db.query("SELECT name||':'||description||':'||version||':'||(archived_at IS NULL) FROM flowdesk.projects WHERE id=1"))
                .isEqualTo("Keep me:Existing project:0:true");
        assertThat(db.query("SELECT version FROM flowdesk.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")).isEqualTo("10");
        db.validateTo("10");
    }
}
