package com.flowdesk.migration;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class V11MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v11_migration").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test void addsNullableArchiveTimestampWithoutChangingExistingTasks() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,false,"9");
        assertThat(db.migrateTo("10")).isEqualTo(1);
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('task-archive@example.test','Task archive'); INSERT INTO flowdesk.workspaces(name) VALUES('Task archive workspace'); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER')");
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Task archive parent'); INSERT INTO flowdesk.tasks(project_id,title,version) VALUES(1,'Preserve me',4)");
        assertThat(db.migrateTo("11")).isEqualTo(1);
        assertThat(db.query("SELECT title||':'||status||':'||version||':'||(archived_at IS NULL) FROM flowdesk.tasks WHERE id=1"))
                .isEqualTo("Preserve me:TODO:4:true");
        assertThat(db.query("SELECT version FROM flowdesk.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")).isEqualTo("11");
        db.validateTo("11");
        assertThat(db.migrateTo("11")).isZero();
    }
}
