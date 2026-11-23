package com.flowdesk.persistence;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkspaceMemberListAccessPolicyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("member_list_acl_tests").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test
    void convertsBroadCurrentReadToExactMappedColumnSelectAndIsIdempotent() throws Exception {
        var db = new IsolatedReadDatabase(POSTGRES, false);
        db.grant().success();
        db.grantCreate().success();
        db.grantRename().success();
        db.grantProject().success();
        db.grantProjectUpdate().success();
        db.grantTask().success();
        db.grantTaskUpdate().success();
        db.grantTaskStatusChange().success();
        db.grantTaskStatusHistoryRead().success();
        db.grantTaskAssigneeDueDate().success();

        assertThat(db.grantWorkspaceMemberListRead().success()).contains("workspace-member-list-read");
        String afterFirst = acl(db);
        assertThat(db.query("""
                SELECT count(*)=0 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname IN ('users','workspaces','workspace_memberships')
                  AND has_table_privilege('flowdesk_app',c.oid,'SELECT')
                """)).isEqualTo("t");
        assertThat(db.query("""
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace, LATERAL aclexplode(a.attacl) x
                WHERE n.nspname='flowdesk' AND c.relname IN ('users','workspaces','workspace_memberships')
                  AND x.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                  AND x.privilege_type='SELECT' AND NOT x.is_grantable
                """)).isEqualTo("16");
        assertThat(db.query("SELECT count(*) FROM flowdesk.workspace_memberships m JOIN flowdesk.users u ON u.id=m.user_id WHERE m.workspace_id=1")).isEqualTo("4");
        try (var app = db.app(); var st = app.createStatement(); var rs = st.executeQuery("SELECT u.id,u.display_name,m.role FROM flowdesk.workspace_memberships m JOIN flowdesk.users u ON u.id=m.user_id WHERE m.workspace_id=1 ORDER BY u.id")) {
            int rows = 0;
            while (rs.next()) rows++;
            assertThat(rows).isEqualTo(4);
        }
        assertThat(db.grantWorkspaceMemberListRead().success()).contains("workspace-member-list-read");
        assertThat(acl(db)).isEqualTo(afterFirst);
        assertThat(db.grantTaskAssigneeDueDate().success()).contains("TASK_ASSIGNEE_DUE_DATE");
    }

    @Test
    void partialColumnStateIsRejectedWithoutAutomaticRepair() throws Exception {
        var db = new IsolatedReadDatabase(POSTGRES, true);
        db.grantWorkspaceMemberListRead().success();
        db.sql("REVOKE SELECT(display_name) ON flowdesk.users FROM flowdesk_app");
        String before = acl(db);
        assertThat(db.grantWorkspaceMemberListRead().failure()).contains("Application-access task failed");
        assertThat(acl(db)).isEqualTo(before);
    }

    private static String acl(IsolatedReadDatabase db) throws Exception {
        return db.query("""
                SELECT coalesce((SELECT string_agg(c.relname||':'||coalesce(c.relacl::text,''),'|' ORDER BY c.relname)
                  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk'), '')
                  || coalesce((SELECT string_agg(c.relname||'.'||a.attname||':'||coalesce(a.attacl::text,''),'|' ORDER BY c.relname,a.attnum)
                  FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                  WHERE n.nspname='flowdesk' AND a.attnum>0 AND NOT a.attisdropped), '')
                """);
    }
}
