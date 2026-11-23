package com.flowdesk.migration;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class V8MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v8_test").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test void addsNullableAssigneeAndCalendarDateWithoutChangingExistingRows() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,false,"7");
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('a@example.test','A'),('b@example.test','B');"
                +"INSERT INTO flowdesk.workspaces(name) VALUES('W');"
                +"INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER'),(1,2,'MEMBER');"
                +"INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'P');"
                +"INSERT INTO flowdesk.tasks(project_id,title,status,priority) VALUES(1,'Existing','IN_PROGRESS','HIGH');");
        String existing=db.query("SELECT jsonb_build_array(id,project_id,title,description,status,priority,created_at,updated_at,version)::text FROM flowdesk.tasks WHERE id=1");
        assertThat(db.migrateTo("8")).isEqualTo(1);
        db.validateTo("8");
        assertThat(db.migrateTo("8")).isZero();
        assertThat(db.query("SELECT jsonb_build_array(id,project_id,title,description,status,priority,created_at,updated_at,version)::text FROM flowdesk.tasks WHERE id=1")).isEqualTo(existing);
        assertThat(db.query("SELECT assignee_id IS NULL AND due_date IS NULL FROM flowdesk.tasks WHERE id=1")).isEqualTo("t");
        assertThat(db.query("SELECT data_type FROM information_schema.columns WHERE table_schema='flowdesk' AND table_name='tasks' AND column_name='due_date'")).isEqualTo("date");

        db.sql("UPDATE flowdesk.tasks SET assignee_id=2,due_date='2028-02-29' WHERE id=1");
        assertThat(db.query("SELECT assignee_id::text||':'||due_date::text FROM flowdesk.tasks WHERE id=1")).isEqualTo("2:2028-02-29");
        db.sql("DELETE FROM flowdesk.workspace_memberships WHERE workspace_id=1 AND user_id=2");
        assertThat(db.query("SELECT assignee_id::text FROM flowdesk.tasks WHERE id=1")).isEqualTo("2");
        assertThatThrownBy(()->db.sql("DELETE FROM flowdesk.users WHERE id=2"))
                .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23503"));
        assertThat(db.query("SELECT count(*) FROM flowdesk.flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7','8') AND success")).isEqualTo("8");
    }
}
