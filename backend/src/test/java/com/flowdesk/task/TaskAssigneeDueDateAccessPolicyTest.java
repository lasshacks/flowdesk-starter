package com.flowdesk.task;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class TaskAssigneeDueDateAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("task_assignee_access").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    private IsolatedReadDatabase priorState() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,true);
        db.grantCreate().success();db.grantRename().success();db.grantProject().success();db.grantProjectUpdate().success();
        db.grantTask().success();db.grantTaskUpdate().success();db.grantTaskStatusChange().success();db.grantTaskStatusHistoryRead().success();
        return db;
    }

    @Test void appliesExactTaskColumnPrivilegesAndAllowsAssignmentDateDml() throws Exception {
        var db=priorState();
        String snapshot=db.snapshot();
        assertThat(db.grantTaskAssigneeDueDate().success()).contains("TASK_ASSIGNEE_DUE_DATE");
        db.sql("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Assignment access')");
        assertThat(db.snapshot()).isEqualTo(snapshot);
        assertThat(db.query("SELECT NOT has_table_privilege('flowdesk_app','flowdesk.tasks','SELECT,INSERT')")).isEqualTo("t");
        assertThat(db.query("SELECT count(*)=12 AND bool_and(attname IN ('id','project_id','title','description','status','priority','created_at','updated_at','version','assignee_id','due_date','archived_at')) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'SELECT')")).isEqualTo("t");
        assertThat(db.query("SELECT count(*)=8 AND bool_and(attname IN ('project_id','title','description','status','priority','version','assignee_id','due_date')) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'INSERT')")).isEqualTo("t");
        assertThat(db.query("SELECT count(*)=8 AND bool_and(attname IN ('title','description','priority','updated_at','version','status','assignee_id','due_date')) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE')")).isEqualTo("t");

        try(var c=db.app();var st=c.createStatement()) {
            c.setAutoCommit(false);
            long id;
            try(var rs=st.executeQuery("INSERT INTO flowdesk.tasks(project_id,title,assignee_id,due_date) VALUES(1,'Assigned',1,'2027-04-05') RETURNING id")) {
                assertThat(rs.next()).isTrue();id=rs.getLong(1);
            }
            assertThat(st.executeUpdate("UPDATE flowdesk.tasks SET assignee_id=NULL,due_date=NULL WHERE id="+id)).isEqualTo(1);
            assertThatThrownBy(()->st.executeUpdate("UPDATE flowdesk.tasks SET project_id=project_id WHERE id="+id))
                    .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
            c.rollback();
        }
        assertThat(db.query("SELECT count(*) FROM flowdesk.tasks")).isEqualTo("0");
        assertThat(db.query("SELECT NOT has_sequence_privilege('flowdesk_app','flowdesk.tasks_id_seq','USAGE,SELECT,UPDATE')")).isEqualTo("t");
    }

    @Test void partialPublicOrBadCredentialStatesAreRejectedWithoutRepair() throws Exception {
        var db=priorState();db.grantTaskAssigneeDueDate().success();
        String before=acl(db);
        db.sql("REVOKE SELECT(assignee_id) ON flowdesk.tasks FROM flowdesk_app");
        String partial=acl(db);db.grantTaskAssigneeDueDate().failure();assertThat(acl(db)).isEqualTo(partial);
        db.sql("GRANT SELECT(assignee_id) ON flowdesk.tasks TO flowdesk_app");
        db.sql("GRANT SELECT(assignee_id) ON flowdesk.tasks TO PUBLIC");
        String publicGrant=acl(db);db.grantTaskAssigneeDueDate().failure();assertThat(acl(db)).isEqualTo(publicGrant);
        db.sql("REVOKE SELECT(assignee_id) ON flowdesk.tasks FROM PUBLIC");
        db.grantTaskAssigneeDueDate(Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64))).failure();
        assertThat(acl(db)).isEqualTo(before);
        db.sql("UPDATE flowdesk.flyway_schema_history SET checksum=1 WHERE version='8'");
        db.grantTaskAssigneeDueDate().failure();assertThat(acl(db)).isEqualTo(before);
    }

    private String acl(IsolatedReadDatabase db)throws Exception{return db.query("SELECT coalesce((SELECT relacl::text FROM pg_class WHERE oid='flowdesk.tasks'::regclass),'')||coalesce((SELECT string_agg(attname||coalesce(attacl::text,''),',' ORDER BY attnum) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped),'')||coalesce((SELECT relacl::text FROM pg_class WHERE oid='flowdesk.tasks_id_seq'::regclass),'')");}
}
