package com.flowdesk.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.MigrateResult;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class V1MigrationTest {

    private static final String MIGRATOR = "flowdesk_migrator";
    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final String FILE_NAME = "V1__create_identity_and_workspace_tables.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("flowdesk_tests")
            .withUsername("postgres")
            .withPassword(UUID.randomUUID().toString());

    private String jdbcUrl;
    private Flyway flyway;
    private MigrateResult firstMigration;

    @BeforeAll
    static void createMigratorRole() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            setSessionPassword(admin, "flowdesk.migrator_password", MIGRATOR_PASSWORD);
            admin.createStatement().execute("""
                    DO $role$
                    BEGIN
                        EXECUTE format(
                            'CREATE ROLE flowdesk_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE '
                            'NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',
                            current_setting('flowdesk.migrator_password')
                        );
                    END
                    $role$
                    """);
        }
    }

    @BeforeEach
    void migrateFreshIsolatedDatabase() throws Exception {
        String database = "v1_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        jdbcUrl = POSTGRES.getJdbcUrl().replace("/flowdesk_tests", "/" + database);
        try (Connection admin = DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = admin.createStatement()) {
            setSessionPassword(admin, "flowdesk.app_password", APP_PASSWORD);
            statement.execute(new ClassPathResource("db/app-role.sql").getContentAsString(StandardCharsets.UTF_8));
            statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + MIGRATOR);
            statement.execute("CREATE SCHEMA flowdesk AUTHORIZATION " + MIGRATOR);
        }
        flyway = migration("classpath:db/migration", MIGRATOR, MIGRATOR_PASSWORD);
        firstMigration = flyway.migrate();
    }

    @Test
    void createsEmptyTablesAndHistoryOnceUsingSchemaOwner() throws Exception {
        assertThat(firstMigration.migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        try (Connection connection = migrator(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'flowdesk' ORDER BY tablename
                    """)) {
                List<String> tables = new ArrayList<>();
                while (result.next()) {
                    tables.add(result.getString(1));
                    assertThat(result.getString(2)).isEqualTo(MIGRATOR);
                }
                assertThat(tables).containsExactly("flyway_schema_history", "users", "workspace_memberships", "workspaces");
            }
            for (String table : List.of("users", "workspaces", "workspace_memberships")) {
                try (var result = statement.executeQuery("SELECT count(*) FROM flowdesk." + table)) {
                    result.next();
                    assertThat(result.getInt(1)).isZero();
                }
            }
            try (var result = statement.executeQuery("""
                    SELECT version, success FROM flowdesk.flyway_schema_history WHERE version IS NOT NULL
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("1");
                assertThat(result.getBoolean(2)).isTrue();
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void generatesIdentityAndTimestampDefaultsAndRejectsExplicitIdentity() throws Exception {
        long user = user("alice@example.test", "Alice");
        assertThat(user).isPositive();
        try (Connection connection = migrator(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT created_at, updated_at FROM flowdesk.users WHERE id=" + user)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getTimestamp(1)).isNotNull();
            assertThat(result.getTimestamp(2)).isEqualTo(result.getTimestamp(1));
        }
        violation("428C9", "INSERT INTO flowdesk.users(id,email,display_name) VALUES(900,'bob@example.test','Bob')");
        violation("23505", "INSERT INTO flowdesk.users(id,email,display_name) OVERRIDING SYSTEM VALUE VALUES("
                + user + ",'bob@example.test','Bob')");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice@example.test", " alice@example.test", "alice@example.test ", "", "   "})
    void rejectsNonCanonicalEmail(String email) {
        assertThatThrownBy(() -> user(email, "Alice"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void rejectsDuplicateCanonicalEmail() throws Exception {
        user("alice@example.test", "Alice");
        assertThatThrownBy(() -> user("alice@example.test", "Another Alice"))
                .isInstanceOfSatisfying(SQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23505");
                    assertThat(error.getMessage()).contains("uq_users_email");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankDisplayAndWorkspaceNames(String name) {
        assertThatThrownBy(() -> user("alice@example.test", name))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        assertThatThrownBy(() -> workspace(name))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void rejectsNullRequiredFields() throws Exception {
        violation("23502", "INSERT INTO flowdesk.users(email,display_name) VALUES(NULL,'Alice')");
        violation("23502", "INSERT INTO flowdesk.users(email,display_name) VALUES('alice@example.test',NULL)");
        violation("23502", "INSERT INTO flowdesk.workspaces(name) VALUES(NULL)");
        long user = user("alice@example.test", "Alice");
        long workspace = workspace("Team");
        violation("23502", "INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(NULL," + user + ",'MEMBER')");
        violation("23502", "INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(" + workspace + ",NULL,'MEMBER')");
        violation("23502", "INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(" + workspace + "," + user + ",NULL)");
    }

    @Test
    void rejectsDuplicateMembershipAndMissingParents() throws Exception {
        long user = user("alice@example.test", "Alice");
        long workspace = workspace("Team");
        membership(workspace, user, "MEMBER");
        assertThatThrownBy(() -> membership(workspace, user, "VIEWER"))
                .isInstanceOfSatisfying(SQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23505");
                    assertThat(error.getMessage()).contains("uq_workspace_memberships_workspace_user");
                });
        assertThatThrownBy(() -> membership(workspace, Long.MAX_VALUE, "MEMBER"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
        assertThatThrownBy(() -> membership(Long.MAX_VALUE, user, "MEMBER"))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MEMBER", "VIEWER", "OWNER"})
    void acceptsDefinedRoles(String role) throws Exception {
        membership(workspace("Team"), user("alice@example.test", "Alice"), role);
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "SUPER_ADMIN", ""})
    void rejectsUndefinedRoles(String role) throws Exception {
        long user = user("alice@example.test", "Alice");
        long workspace = workspace("Team");
        assertThatThrownBy(() -> membership(workspace, user, role))
                .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    @Test
    void restrictsOwnerPerWorkspaceButAllowsTheSameOwnerInAnotherWorkspace() throws Exception {
        long alice = user("alice@example.test", "Alice");
        long bob = user("bob@example.test", "Bob");
        long team = workspace("Team");
        membership(team, alice, "OWNER");
        assertThatThrownBy(() -> membership(team, bob, "OWNER"))
                .isInstanceOfSatisfying(SQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23505");
                    assertThat(error.getMessage()).contains("uq_workspace_memberships_owner");
                });
        membership(workspace("Other team"), alice, "OWNER");
    }

    @Test
    void restrictsParentDeletionWithoutCascadingMemberships() throws Exception {
        long user = user("alice@example.test", "Alice");
        long workspace = workspace("Team");
        membership(workspace, user, "OWNER");
        violation("23503", "DELETE FROM flowdesk.users WHERE id=" + user);
        violation("23503", "DELETE FROM flowdesk.workspaces WHERE id=" + workspace);
        try (Connection connection = migrator(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM flowdesk.workspace_memberships")) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void appCanConnectButCannotReadWriteMigrateOrModifyHistory() throws Exception {
        try (Connection app = DriverManager.getConnection(jdbcUrl, "flowdesk_app", APP_PASSWORD);
             var statement = app.createStatement()) {
            try (var result = statement.executeQuery("SELECT 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
            for (String sql : List.of(
                    "SELECT * FROM flowdesk.users",
                    "INSERT INTO flowdesk.users(email,display_name) VALUES('app@example.test','App')",
                    "CREATE TABLE flowdesk.forbidden_probe(id integer)",
                    "DELETE FROM flowdesk.flyway_schema_history")) {
                assertThatThrownBy(() -> statement.execute(sql))
                        .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            }
        }
        assertThatThrownBy(() -> migration("classpath:db/migration", "flowdesk_app", APP_PASSWORD).migrate())
                .isInstanceOf(FlywayException.class);
        flyway.validate();
    }

    @Test
    void migratorOwnsOnlyItsSchemaWithoutAdministratorOrDatabaseCreatePrivileges() throws Exception {
        try (Connection connection = migrator(); var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls,
                            has_database_privilege(current_database(), 'CREATE')
                     FROM pg_roles WHERE rolname=current_user
                     """)) {
            assertThat(result.next()).isTrue();
            for (int column = 1; column <= 6; column++) {
                assertThat(result.getBoolean(column)).isFalse();
            }
        }
        violation("42501", "CREATE SCHEMA forbidden_schema");
    }

    @Test
    void detectsModifiedAppliedMigrationWithoutRepair(@TempDir Path directory) throws Exception {
        String original = new ClassPathResource("db/migration/" + FILE_NAME).getContentAsString(StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(FILE_NAME), original.replace("varchar(80)", "varchar(81)"));
        Flyway modified = migration("filesystem:" + directory.toAbsolutePath(), MIGRATOR, MIGRATOR_PASSWORD);
        assertThatThrownBy(modified::validate).isInstanceOf(FlywayValidateException.class).hasMessageContaining("checksum");
        flyway.validate();
    }

    private Flyway migration(String location, String user, String password) {
        return Flyway.configure().dataSource(jdbcUrl, user, password)
                .defaultSchema("flowdesk").schemas("flowdesk").createSchemas(false)
                .locations(location).target("1").cleanDisabled(true).baselineOnMigrate(false).load();
    }

    private Connection migrator() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, MIGRATOR, MIGRATOR_PASSWORD);
    }

    private long user(String email, String name) throws SQLException {
        return insertId("INSERT INTO flowdesk.users(email,display_name) VALUES(?,?) RETURNING id", email, name);
    }

    private long workspace(String name) throws SQLException {
        return insertId("INSERT INTO flowdesk.workspaces(name) VALUES(?) RETURNING id", name);
    }

    private long membership(long workspace, long user, String role) throws SQLException {
        return insertId("INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(?,?,?) RETURNING id",
                workspace, user, role);
    }

    private long insertId(String sql, Object... parameters) throws SQLException {
        try (Connection connection = migrator(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void violation(String state, String sql) {
        assertThatThrownBy(() -> {
            try (Connection connection = migrator(); var statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }).isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo(state));
    }

    private static void setSessionPassword(Connection connection, String key, String password) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
            statement.setString(1, key);
            statement.setString(2, password);
            statement.execute();
        }
    }
}
