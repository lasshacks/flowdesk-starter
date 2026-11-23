package com.flowdesk.seed;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LocalSeedRunnerTest {
    private static final String PASSWORD = password();
    private static final String APP_PASSWORD = password();
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("seed_tests").withUsername("postgres").withPassword(password());
    private String url;

    @BeforeAll
    static void role() throws Exception {
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", POSTGRES.getPassword())) {
            setPassword(admin, "flowdesk.seed_test_password", PASSWORD);
            admin.createStatement().execute("""
                    DO $role$ BEGIN EXECUTE format('CREATE ROLE flowdesk_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',current_setting('flowdesk.seed_test_password')); END $role$;
                    """);
        }
    }

    @BeforeEach
    void database() throws Exception {
        String database = "seed_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", POSTGRES.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE " + database);
        }
        // Match the local runner's deliberately narrow target format; Testcontainers may add URL query options.
        url = "jdbc:postgresql://127.0.0.1:" + POSTGRES.getMappedPort(5432) + "/" + database;
        try (var admin = admin()) {
            setPassword(admin, "flowdesk.app_password", APP_PASSWORD);
            admin.createStatement().execute(new ClassPathResource("db/app-role.sql").getContentAsString(StandardCharsets.UTF_8));
            admin.createStatement().execute("GRANT CONNECT ON DATABASE " + database + " TO flowdesk_migrator");
            admin.createStatement().execute("CREATE SCHEMA flowdesk AUTHORIZATION flowdesk_migrator");
        }
        Flyway.configure().dataSource(url, "flowdesk_migrator", PASSWORD).defaultSchema("flowdesk").schemas("flowdesk")
                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).load().migrate();
    }

    @Test
    void seedsVerifiesAndRepeatsWithoutChangingIdsTimestampsHistoryOrPermissions() throws Exception {
        String history = history();
        assertThat(run("Apply").success()).contains("Seed rows inserted: 12");
        assertThat(counts()).isEqualTo("5/2/5");
        String state = snapshot();
        assertThat(run("Verify").success()).contains("Seed verification successful");
        assertThat(run("Apply").success()).contains("Seed rows inserted: 0");
        assertThat(snapshot()).isEqualTo(state);
        assertThat(history()).isEqualTo(history);
        assertThat(query("SELECT count(*) FROM flowdesk.workspaces w WHERE (SELECT count(*) FROM flowdesk.workspace_memberships m WHERE m.workspace_id=w.id AND m.role='OWNER')=1")).isEqualTo("2");
        assertThat(query("SELECT count(*) FROM flowdesk.workspace_memberships m JOIN flowdesk.users u ON u.id=m.user_id JOIN flowdesk.workspaces w ON w.id=m.workspace_id WHERE u.email='eve@example.test' AND w.name='FlowDesk Demo A'")).isEqualTo("0");
        assertThat(query("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND c.relkind='r' AND (has_table_privilege('flowdesk_app',c.oid,'SELECT') OR has_table_privilege('flowdesk_app',c.oid,'INSERT') OR has_table_privilege('flowdesk_app',c.oid,'UPDATE') OR has_table_privilege('flowdesk_app',c.oid,'DELETE'))")).isEqualTo("0");
    }

    @Test
    void verifyDoesNotInsertIntoEmptyTables() throws Exception {
        assertThat(run("Verify").failure()).contains("mismatch");
        assertThat(counts()).isEqualTo("0/0/0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "email", "role", "extra_user", "extra_workspace", "duplicate_workspace_name", "missing_membership", "wrong_relation"})
    void refusesChangedOrAdditionalDataWithoutMerging(String mutation) throws Exception {
        run("Apply").success();
        sql(switch (mutation) {
            case "name" -> "UPDATE flowdesk.users SET display_name='Edited' WHERE email='alice@example.test'";
            case "email" -> "UPDATE flowdesk.users SET email='edited@example.test' WHERE email='alice@example.test'";
            case "role" -> "UPDATE flowdesk.workspace_memberships SET role='VIEWER' WHERE user_id=(SELECT id FROM flowdesk.users WHERE email='bob@example.test')";
            case "extra_user" -> "INSERT INTO flowdesk.users(email,display_name) VALUES('extra@example.test','Extra')";
            case "extra_workspace" -> "INSERT INTO flowdesk.workspaces(name) VALUES('Extra workspace')";
            case "duplicate_workspace_name" -> "INSERT INTO flowdesk.workspaces(name) VALUES('FlowDesk Demo A')";
            case "missing_membership" -> "DELETE FROM flowdesk.workspace_memberships WHERE user_id=(SELECT id FROM flowdesk.users WHERE email='carol@example.test')";
            case "wrong_relation" -> "UPDATE flowdesk.workspace_memberships SET workspace_id=(SELECT id FROM flowdesk.workspaces WHERE name='FlowDesk Demo B') WHERE user_id=(SELECT id FROM flowdesk.users WHERE email='bob@example.test')";
            default -> throw new IllegalArgumentException();
        });
        String before = snapshot();
        assertThat(run("Apply").failure()).contains("mismatch");
        assertThat(run("Verify").failure()).contains("mismatch");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void refusesAnExistingUserOrSameNamedWorkspaceInsteadOfAdoptingIt() throws Exception {
        sql("INSERT INTO flowdesk.users(email,display_name) VALUES('alice@example.test','Alice'); INSERT INTO flowdesk.workspaces(name) VALUES('FlowDesk Demo A')");
        String before = snapshot();
        run("Apply").failure();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void rollsBackAllRowsWhenMembershipInsertionFails() throws Exception {
        sql("ALTER TABLE flowdesk.workspace_memberships ADD CONSTRAINT test_seed_failure CHECK(role<>'VIEWER')");
        String history = history();
        assertThat(run("Apply").failure()).contains("SQLState 23514");
        assertThat(counts()).isEqualTo("0/0/0");
        assertThat(history()).isEqualTo(history);
        sql("ALTER TABLE flowdesk.workspace_memberships DROP CONSTRAINT test_seed_failure");
        run("Apply").success();
        assertThat(counts()).isEqualTo("5/2/5");
    }

    @Test
    void concurrentApplyCreatesOneFixture() throws Exception {
        Process first = start("Apply", Map.of(), null);
        Process second = start("Apply", Map.of(), null);
        String a = finish(first).success();
        String b = finish(second).success();
        assertThat(List.of(a.contains("inserted: 12"), b.contains("inserted: 12"))).containsExactlyInAnyOrder(true, false);
        assertThat(a + b).contains("inserted: 0");
        run("Verify").success();
        assertThat(counts()).isEqualTo("5/2/5");
    }

    @Test
    void lockTimeoutLeavesDataUntouchedAndReleasesLocks() throws Exception {
        try (var blocker = admin()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("LOCK TABLE flowdesk.users IN ROW EXCLUSIVE MODE");
            assertThat(run("Apply").failure()).contains("SQLState 55P03");
            assertThat(counts()).isEqualTo("0/0/0");
            blocker.rollback();
        }
        run("Apply").success();
        run("Verify").success();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingPassword", "wrongPassword", "wrongUser", "remoteUrl", "urlQuery", "inheritedApp", "inheritedAdmin", "unknownAction", "missingAction"})
    void rejectsUnsafeConfigurationWithoutWriting(String scenario) throws Exception {
        Map<String, String> overrides = switch (scenario) {
            case "missingPassword" -> Map.of("FLOWDESK_MIGRATOR_DB_PASSWORD", "");
            case "wrongPassword" -> Map.of("FLOWDESK_MIGRATOR_DB_PASSWORD", (PASSWORD.charAt(0) == '0' ? "1" : "0") + PASSWORD.substring(1));
            case "wrongUser" -> Map.of("FLOWDESK_MIGRATOR_DB_USERNAME", "flowdesk_app");
            case "remoteUrl" -> Map.of("FLOWDESK_MIGRATOR_DB_URL", url.replace("127.0.0.1", "192.0.2.1"));
            case "urlQuery" -> Map.of("FLOWDESK_MIGRATOR_DB_URL", url + "?user=postgres");
            case "inheritedApp" -> Map.of("FLOWDESK_DB_PASSWORD", APP_PASSWORD);
            case "inheritedAdmin" -> Map.of("FLOWDESK_MIGRATION_ADMIN_PASSWORD", POSTGRES.getPassword());
            default -> Map.of();
        };
        String action = scenario.equals("missingAction") ? "" : scenario.equals("unknownAction") ? "Reset" : "Apply";
        finish(start(action, overrides, null)).failure();
        assertThat(counts()).isEqualTo("0/0/0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"checksum", "failedHistory", "futureHistory", "schemaOwner", "publicGrant", "missingHistory"})
    void rejectsUnexpectedSchemaOrHistory(String scenario) throws Exception {
        sql(switch (scenario) {
            case "checksum" -> "UPDATE flowdesk.flyway_schema_history SET checksum=checksum+1";
            case "failedHistory" -> "UPDATE flowdesk.flyway_schema_history SET success=false";
            case "futureHistory" -> "INSERT INTO flowdesk.flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) VALUES(10,'10','future','SQL','V10__future.sql','flowdesk_migrator',0,true)";
            case "schemaOwner" -> "ALTER SCHEMA flowdesk OWNER TO postgres";
            case "publicGrant" -> "GRANT USAGE ON SCHEMA flowdesk TO PUBLIC";
            case "missingHistory" -> "ALTER TABLE flowdesk.flyway_schema_history RENAME TO saved_history";
            default -> throw new IllegalArgumentException();
        });
        String before = snapshot();
        run("Apply").failure();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void refusesPendingMigrationWithoutApplyingIt(@TempDir Path directory) throws Exception {
        Path migrations = Files.createDirectories(directory.resolve("db/migration"));
        Files.writeString(migrations.resolve("V10__future.sql"), "CREATE TABLE flowdesk.must_not_exist(id int);");
        finish(start("Apply", Map.of(), directory)).failure();
        assertThat(query("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND c.relname='must_not_exist'")).isEqualTo("0");
        assertThat(counts()).isEqualTo("0/0/0");
    }

    @Test
    void doesNotCreateMissingSchema() throws Exception {
        sql("DROP SCHEMA flowdesk CASCADE");
        run("Apply").failure();
        assertThat(query("SELECT count(*) FROM pg_namespace WHERE nspname='flowdesk'")).isEqualTo("0");
    }

    private Result run(String action) throws Exception { return finish(start(action, Map.of(), null)); }

    private Process start(String action, Map<String, String> overrides, Path extraClasspath) throws Exception {
        String classpath = System.getProperty("flowdesk.seed.classpath");
        if (extraClasspath != null) classpath = extraClasspath + java.io.File.pathSeparator + classpath;
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, "com.flowdesk.seed.LocalSeedRunner"));
        if (!action.isEmpty()) command.add(action);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.matches("(?i)(FLOWDESK_.*|POSTGRES_.*|PGPASSWORD|FLYWAY_.*|SPRING_.*|DEBUG)"));
        builder.environment().putAll(Map.of("FLOWDESK_MIGRATOR_DB_URL", url,
                "FLOWDESK_MIGRATOR_DB_USERNAME", "flowdesk_migrator", "FLOWDESK_MIGRATOR_DB_PASSWORD", PASSWORD));
        builder.environment().putAll(overrides);
        return builder.start();
    }

    private Result finish(Process process) throws Exception {
        try {
            assertThat(process.waitFor(25, TimeUnit.SECONDS)).as("seed process completes").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String secret : List.of(PASSWORD, APP_PASSWORD, POSTGRES.getPassword())) {
                assertThat(output.contains(secret)).as("credential not present in child output").isFalse();
            }
            return new Result(process.exitValue(), output);
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    private record Result(int code, String output) {
        String success() { assertThat(code).as(output).isZero(); return output; }
        String failure() { assertThat(code).as(output).isNotZero(); return output; }
    }

    private String counts() throws Exception {
        return query("SELECT (SELECT count(*) FROM flowdesk.users)||'/'||(SELECT count(*) FROM flowdesk.workspaces)||'/'||(SELECT count(*) FROM flowdesk.workspace_memberships)");
    }

    private String snapshot() throws Exception {
        return query("SELECT coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.users x),'[]')||coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspaces x),'[]')||coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspace_memberships x),'[]')");
    }

    private String history() throws Exception { return query("SELECT json_agg(x ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history x"); }
    private void sql(String sql) throws Exception { try (var admin = admin()) { admin.createStatement().execute(sql); } }
    private String query(String sql) throws Exception {
        try (var admin = admin(); var st = admin.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }
    private Connection admin() throws Exception { return DriverManager.getConnection(url, "postgres", POSTGRES.getPassword()); }
    private static String password() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private static void setPassword(Connection connection, String key, String value) throws Exception {
        try (var st = connection.prepareStatement("SELECT set_config(?,?,false)")) { st.setString(1,key); st.setString(2,value); st.execute(); }
    }
}
