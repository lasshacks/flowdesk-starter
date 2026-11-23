package com.flowdesk.persistence;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkspaceMembershipManagementAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("membership_acl").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test
    void grantsExactMembershipAndInvitationPrivilegesAndIsIdempotent() throws Exception {
        var db = new IsolatedReadDatabase(POSTGRES, false);
        db.grant().success();
        assertThat(db.grantCreate().success()).as("workspace-create grant").isNotBlank();
        assertThat(db.grantRename().success()).as("workspace-rename grant").isNotBlank();
        assertThat(db.grantProject().success()).as("project grant").isNotBlank();
        assertThat(db.grantProjectUpdate().success()).as("project-update grant").isNotBlank();
        assertThat(db.grantTask().success()).as("task grant").isNotBlank();
        assertThat(db.grantTaskUpdate().success()).as("task-update grant").isNotBlank();
        assertThat(db.grantTaskStatusChange().success()).as("status-change grant").isNotBlank();
        assertThat(db.grantTaskStatusHistoryRead().success()).as("history-read grant").isNotBlank();
        assertThat(db.grantTaskAssigneeDueDate().success()).as("assignee/due-date grant").isNotBlank();
        assertThat(db.grantWorkspaceMemberListRead().success()).as("member-list grant").isNotBlank();

        assertThat(db.grantWorkspaceMembershipManagement().success()).contains("workspace-membership-management");
        String afterFirst = acl(db);
        assertThat(db.query("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace, LATERAL aclexplode(a.attacl) x WHERE n.nspname='flowdesk' AND c.relname='workspace_invitations' AND x.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app') AND x.privilege_type='SELECT' AND NOT x.is_grantable")).isEqualTo("10");
        assertThat(db.query("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace, LATERAL aclexplode(a.attacl) x WHERE n.nspname='flowdesk' AND c.relname='workspace_invitations' AND x.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app') AND x.privilege_type='INSERT' AND NOT x.is_grantable")).isEqualTo("8");
        assertThat(db.query("SELECT has_table_privilege('flowdesk_app','flowdesk.workspace_memberships','DELETE') AND NOT has_table_privilege('flowdesk_app','flowdesk.workspace_invitations','SELECT,INSERT,UPDATE,DELETE')")).isEqualTo("t");
        try (var app = db.app()) {
            assertThat(app.createStatement().executeUpdate("UPDATE flowdesk.workspace_memberships SET role='ADMIN' WHERE id=-1")).isZero();
            assertThat(app.createStatement().executeUpdate("UPDATE flowdesk.workspace_invitations SET status='REVOKED' WHERE id=-1")).isZero();
            assertThat(app.createStatement().executeUpdate("DELETE FROM flowdesk.workspace_memberships WHERE id=-1")).isZero();
        }
        assertThat(db.grantWorkspaceMembershipManagement().success()).contains("workspace-membership-management");
        assertThat(acl(db)).isEqualTo(afterFirst);
        assertThat(db.grantTaskAssigneeDueDate().success()).contains("WORKSPACE_MEMBERSHIP_MANAGEMENT");
        assertThat(acl(db)).isEqualTo(afterFirst);

        db.sql("REVOKE UPDATE(accepted_at) ON flowdesk.workspace_invitations FROM flowdesk_app");
        String partial = acl(db);
        assertThat(db.grantWorkspaceMembershipManagement().failure()).contains("Application-access task failed");
        assertThat(acl(db)).isEqualTo(partial);
        assertThat(db.grantTaskAssigneeDueDate().failure()).contains("Application-access task failed");
        assertThat(acl(db)).isEqualTo(partial);
    }

    private static String acl(IsolatedReadDatabase db) throws Exception {
        return db.query("SELECT coalesce((SELECT string_agg(c.relname||':'||coalesce(c.relacl::text,''),'|' ORDER BY c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk'),'') || coalesce((SELECT string_agg(c.relname||'.'||a.attname||':'||coalesce(a.attacl::text,''),'|' ORDER BY c.relname,a.attnum) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND a.attnum>0 AND NOT a.attisdropped),'')");
    }
}
