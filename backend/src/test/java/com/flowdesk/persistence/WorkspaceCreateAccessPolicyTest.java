package com.flowdesk.persistence;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class WorkspaceCreateAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("create_policy").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    IsolatedReadDatabase database;
    @BeforeEach void prepare() throws Exception { database = new IsolatedReadDatabase(POSTGRES,true); }
    String acl() throws Exception {
        return database.query("SELECT json_agg(json_build_array(c.relname,c.relacl) ORDER BY c.relname)::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk'");
    }

    @Test void grantRepeatsAndAllToolsPreserveApprovedWriteState() throws Exception {
        String before=database.snapshot();
        assertThat(database.grantCreate().success()).contains("WORKSPACE_CREATE");
        String granted=acl();
        database.grantCreate().success();
        assertThat(database.grant().success()).contains("WORKSPACE_CREATE");
        database.migrate("Info").success();database.migrate("Validate").success();
        assertThat(database.migrate("Migrate").success()).contains("Migrations executed: 0");
        database.seed("Verify").success();assertThat(database.seed("Apply").success()).contains("Seed rows inserted: 0");
        assertThat(acl()).isEqualTo(granted);assertThat(database.snapshot()).isEqualTo(before);
    }

    @Test void requiresReadStateAndAuthenticatesExistingAppCredential() throws Exception {
        var connectOnly=new IsolatedReadDatabase(POSTGRES,false);
        String before=connectOnly.snapshot();
        connectOnly.grantCreate().failure();assertThat(connectOnly.snapshot()).isEqualTo(before);
        for(var config:java.util.List.of(Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64)),
                Map.of("FLOWDESK_MIGRATOR_DB_URL",database.url.replace("127.0.0.1","localhost")),
                Map.of("FLOWDESK_MIGRATION_ADMIN_USER","flowdesk_app"))) {
            String data=database.snapshot(),policy=acl();database.grantCreate(config).failure();
            assertThat(database.snapshot()).isEqualTo(data);assertThat(acl()).isEqualTo(policy);
        }
    }

    @Test void identityGenerationWorksWithoutSequenceOrOtherWritePrivileges() throws Exception {
        database.grantCreate().success();String before=database.snapshot();
        try(var connection=database.app()) {
            connection.setAutoCommit(false);
            long workspace;
            try(var st=connection.prepareStatement("INSERT INTO flowdesk.workspaces(name) VALUES ('Identity probe')",new String[]{"id"})) {
                assertThat(st.executeUpdate()).isEqualTo(1);
                try(var ids=st.getGeneratedKeys()){assertThat(ids.next()).isTrue();workspace=ids.getLong(1);assertThat(workspace).isPositive();}
            }
            try(var st=connection.prepareStatement("INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) SELECT ?,id,'OWNER' FROM flowdesk.users WHERE email='alice@example.test'",new String[]{"id"})) {
                st.setLong(1,workspace);assertThat(st.executeUpdate()).isEqualTo(1);
                try(var ids=st.getGeneratedKeys()){assertThat(ids.next()).isTrue();assertThat(ids.getLong(1)).isPositive();}
            }
            connection.rollback();
        }
        assertThat(database.snapshot()).isEqualTo(before);
        for(String sql:new String[]{"INSERT INTO flowdesk.users(email,display_name) VALUES ('blocked@example.test','Blocked')",
                "UPDATE flowdesk.workspaces SET name='x' WHERE false","DELETE FROM flowdesk.workspace_memberships WHERE false",
                "TRUNCATE flowdesk.workspaces","SELECT nextval('flowdesk.workspaces_id_seq')",
                "SELECT currval('flowdesk.workspace_memberships_id_seq')","SELECT setval('flowdesk.workspaces_id_seq',1)",
                "SELECT * FROM flowdesk.flyway_schema_history","CREATE TABLE flowdesk.forbidden(id int)"}) {
            try(var connection=database.app();var st=connection.createStatement()) {
                assertThatThrownBy(()->st.execute(sql)).isInstanceOf(SQLException.class)
                        .satisfies(failure->assertThat(((SQLException)failure).getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings={
            "REVOKE INSERT ON flowdesk.workspaces FROM flowdesk_app",
            "REVOKE INSERT ON flowdesk.workspace_memberships FROM flowdesk_app",
            "GRANT INSERT ON flowdesk.users TO flowdesk_app",
            "GRANT UPDATE ON flowdesk.workspaces TO flowdesk_app",
            "GRANT DELETE ON flowdesk.workspace_memberships TO flowdesk_app",
            "GRANT TRUNCATE ON flowdesk.workspaces TO flowdesk_app",
            "GRANT USAGE ON SEQUENCE flowdesk.workspaces_id_seq TO flowdesk_app",
            "GRANT SELECT ON flowdesk.flyway_schema_history TO flowdesk_app",
            "GRANT INSERT ON flowdesk.workspaces TO flowdesk_app WITH GRANT OPTION",
            "GRANT INSERT ON flowdesk.workspaces TO PUBLIC",
            "GRANT UPDATE(name) ON flowdesk.workspaces TO flowdesk_app",
            "ALTER DEFAULT PRIVILEGES FOR ROLE flowdesk_migrator IN SCHEMA flowdesk GRANT INSERT ON TABLES TO flowdesk_app"
    })
    void partialOrExcessiveCreatePoliciesBlockAllToolsWithoutRepair(String change) throws Exception {
        database.grantCreate().success();database.sql(change);
        String before=database.snapshot(),policy=acl();
        database.grantCreate().failure();database.grant().failure();database.migrate("Validate").failure();database.seed("Verify").failure();
        assertThat(database.snapshot()).isEqualTo(before);assertThat(acl()).isEqualTo(policy);
    }

    @Test void modifiedV1HistoryFailsBeforeAnyInsertGrant() throws Exception {
        database.sql("UPDATE flowdesk.flyway_schema_history SET checksum=1 WHERE version='1'");
        String policy=acl(),before=database.snapshot();database.grantCreate().failure();
        assertThat(acl()).isEqualTo(policy);assertThat(database.snapshot()).isEqualTo(before);
    }

    @Test void businessDataMakesSeedFailWithoutDeletingOrChangingNewSpace() throws Exception {
        database.grantCreate().success();
        database.sql("WITH w AS (INSERT INTO flowdesk.workspaces(name) VALUES ('Business space') RETURNING id) INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) SELECT w.id,u.id,'OWNER' FROM w CROSS JOIN flowdesk.users u WHERE u.email='alice@example.test'");
        String before=database.snapshot();database.seed("Apply").failure();database.seed("Verify").failure();
        database.migrate("Validate").success();assertThat(database.migrate("Migrate").success()).contains("Migrations executed: 0");
        assertThat(database.snapshot()).isEqualTo(before);
    }
}
