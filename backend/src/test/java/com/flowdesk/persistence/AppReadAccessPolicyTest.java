package com.flowdesk.persistence;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AppReadAccessPolicyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("read_policy_tests").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    IsolatedReadDatabase database;
    @BeforeEach void fixture() throws Exception { database=new IsolatedReadDatabase(POSTGRES,false); }
    @AfterEach void restoreClusterRole() throws Exception {
        if(database!=null) database.sql("""
                ALTER ROLE flowdesk_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
                DO $cleanup$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='flowdesk_extra') THEN
                  REVOKE flowdesk_extra FROM flowdesk_app;
                END IF; END $cleanup$;
                """);
    }
    @Test void bothStatesKeepMigrationSeedAndHistoryCompatible() throws Exception {
        String before=database.snapshot();
        for(int phase=0;phase<2;phase++) {
            database.migrate("Info").success();
            database.migrate("Validate").success();
            assertThat(database.migrate("Migrate").success()).contains("Migrations executed: 0");
            database.seed("Verify").success();
            assertThat(database.seed("Apply").success()).contains("Seed rows inserted: 0");
            database.grant().success();
        }
        assertThat(database.snapshot()).isEqualTo(before);
        try(var c=database.app();var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM flowdesk.users")) {
            rs.next();assertThat(rs.getInt(1)).isEqualTo(5);
        }
        assertThat(database.query("SELECT has_schema_privilege('flowdesk_app','flowdesk','USAGE')")).isEqualTo("t");
    }
    @ParameterizedTest
    @ValueSource(strings={"schemaOnly","tableOnly","missingSelect","dml","history","sequence","grantOption","publicSchema","publicTable","otherRole","column","defaults","membership","superuser","schemaOwner","tableOwner","schemaCreate","dbTemp","dbGrantOption","publicConnect","futureTable"})
    void rejectsPartialAndExcessivePrivilegesWithoutRepair(String mutation) throws Exception {
        if(mutation.equals("missingSelect")) database.grant().success();
        database.sql(switch(mutation) {
            case "schemaOnly" -> "GRANT USAGE ON SCHEMA flowdesk TO flowdesk_app";
            case "tableOnly" -> "GRANT SELECT ON flowdesk.users TO flowdesk_app";
            case "missingSelect" -> "REVOKE SELECT ON flowdesk.workspace_memberships FROM flowdesk_app";
            case "dml" -> "GRANT UPDATE ON flowdesk.users TO flowdesk_app";
            case "history" -> "GRANT SELECT ON flowdesk.flyway_schema_history TO flowdesk_app";
            case "sequence" -> "GRANT USAGE ON SEQUENCE flowdesk.users_id_seq TO flowdesk_app";
            case "grantOption" -> "GRANT SELECT ON flowdesk.users TO flowdesk_app WITH GRANT OPTION";
            case "publicSchema" -> "GRANT USAGE ON SCHEMA flowdesk TO PUBLIC";
            case "publicTable" -> "GRANT SELECT ON flowdesk.users TO PUBLIC";
            case "otherRole" -> extraRole()+" GRANT USAGE ON SCHEMA flowdesk TO flowdesk_extra";
            case "column" -> "GRANT SELECT(email) ON flowdesk.users TO flowdesk_app";
            case "defaults" -> "ALTER DEFAULT PRIVILEGES FOR ROLE flowdesk_migrator IN SCHEMA flowdesk GRANT SELECT ON TABLES TO flowdesk_app";
            case "membership" -> extraRole()+" GRANT flowdesk_extra TO flowdesk_app";
            case "superuser" -> "ALTER ROLE flowdesk_app SUPERUSER";
            case "schemaOwner" -> "ALTER SCHEMA flowdesk OWNER TO flowdesk_app";
            case "tableOwner" -> "ALTER TABLE flowdesk.users OWNER TO flowdesk_app";
            case "schemaCreate" -> "GRANT CREATE ON SCHEMA flowdesk TO flowdesk_app";
            case "dbTemp" -> "DO $grant$ BEGIN EXECUTE format('GRANT TEMP ON DATABASE %I TO flowdesk_app',current_database()); END $grant$";
            case "dbGrantOption" -> "DO $grant$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO flowdesk_app WITH GRANT OPTION',current_database()); END $grant$";
            case "publicConnect" -> "DO $grant$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO PUBLIC',current_database()); END $grant$";
            case "futureTable" -> "CREATE TABLE flowdesk.future_probe(id int); ALTER TABLE flowdesk.future_probe OWNER TO flowdesk_migrator; GRANT SELECT ON flowdesk.future_probe TO flowdesk_app";
            default -> throw new IllegalArgumentException();
        });
        String before=database.snapshot(), acl=acl();
        database.grant().failure();
        database.migrate("Validate").failure();
        database.seed("Verify").failure();
        assertThat(database.snapshot()).isEqualTo(before);
        assertThat(acl()).isEqualTo(acl);
    }
    @ParameterizedTest
    @ValueSource(strings={"wrongPassword","wrongUrl","missingPassword","wrongAdmin","badHistory"})
    void provisioningRejectsWrongConfigurationOrHistory(String mutation) throws Exception {
        Map<String,String> overrides=switch(mutation) {
            case "wrongPassword" -> Map.of("FLOWDESK_READ_APP_PASSWORD",(IsolatedReadDatabase.APP_PASSWORD.charAt(0)=='0'?"1":"0")+IsolatedReadDatabase.APP_PASSWORD.substring(1));
            case "wrongUrl" -> Map.of("FLOWDESK_MIGRATOR_DB_URL",database.url.replace("127.0.0.1","192.0.2.1"));
            case "missingPassword" -> Map.of("FLOWDESK_READ_APP_PASSWORD","");
            case "wrongAdmin" -> Map.of("FLOWDESK_MIGRATION_ADMIN_USER","flowdesk_app");
            default -> Map.of();
        };
        if(mutation.equals("badHistory")) database.sql("UPDATE flowdesk.flyway_schema_history SET checksum=checksum+1");
        String before=database.snapshot(),acl=acl();
        database.grant(overrides).failure();
        assertThat(database.snapshot()).isEqualTo(before);assertThat(acl()).isEqualTo(acl);
    }
    private String acl() throws Exception {
        return database.query("SELECT coalesce((SELECT nspacl::text FROM pg_namespace WHERE nspname='flowdesk'),'')||coalesce((SELECT string_agg(c.relname||coalesce(c.relacl::text,''),'|' ORDER BY c.oid) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk'),'')");
    }
    private static String extraRole(){return "DO $role$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='flowdesk_extra') THEN CREATE ROLE flowdesk_extra; END IF; END $role$;";}
}
