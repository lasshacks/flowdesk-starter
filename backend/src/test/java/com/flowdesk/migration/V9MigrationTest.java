package com.flowdesk.migration;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class V9MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v9_migration").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test void invitationSchemaConstrainsTargetsCodesRolesAndPendingDuplicates() throws Exception {
        var db = new IsolatedReadDatabase(POSTGRES, false, "8");
        assertThat(db.query("SELECT to_regclass('flowdesk.workspace_invitations') IS NULL")).isEqualTo("t");
        assertThat(db.migrateTo("9")).isEqualTo(1);
        assertThat(db.migrateTo("11")).isEqualTo(2);
        db.seed("Apply").success();
        assertThat(db.query("SELECT version FROM flowdesk.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")).isEqualTo("11");

        db.sql("""
                INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,expires_at)
                VALUES(1,2,1,'MEMBER',repeat('a',64),CURRENT_TIMESTAMP+INTERVAL '7 days')
                """);
        assertThat(db.query("SELECT status FROM flowdesk.workspace_invitations WHERE token_digest=repeat('a',64)")).isEqualTo("PENDING");
        assertThatThrownBy(() -> db.sql("""
                INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,expires_at)
                VALUES(1,2,1,'VIEWER',repeat('b',64),CURRENT_TIMESTAMP+INTERVAL '7 days')
                """)).isInstanceOf(Exception.class);
        db.sql("UPDATE flowdesk.workspace_invitations SET status='REVOKED' WHERE token_digest=repeat('a',64)");
        db.sql("""
                INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,expires_at)
                VALUES(1,2,1,'VIEWER',repeat('b',64),CURRENT_TIMESTAMP+INTERVAL '7 days')
                """);
        assertThatThrownBy(() -> db.sql("""
                INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,expires_at)
                VALUES(1,3,1,'OWNER',repeat('c',64),CURRENT_TIMESTAMP+INTERVAL '7 days')
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.sql("""
                INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,expires_at)
                VALUES(1,3,1,'MEMBER',repeat('b',64),CURRENT_TIMESTAMP+INTERVAL '7 days')
                """)).isInstanceOf(Exception.class); // Reusing a one-time code digest is forbidden.
        assertThat(db.query("SELECT count(*) FROM flowdesk.workspace_invitations")).isEqualTo("2");
        assertThatThrownBy(() -> db.sql("""
                INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,expires_at)
                VALUES(1,3,1,'MEMBER','not-a-digest',CURRENT_TIMESTAMP+INTERVAL '7 days')
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.sql("""
                INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,expires_at,accepted_at,status)
                VALUES(1,4,1,'VIEWER',repeat('e',64),CURRENT_TIMESTAMP+INTERVAL '7 days',NULL,'ACCEPTED')
                """)).isInstanceOf(Exception.class);
        db.validateTo("11");
    }
}
