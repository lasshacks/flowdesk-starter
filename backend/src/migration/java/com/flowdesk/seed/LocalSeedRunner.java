package com.flowdesk.seed;

import com.flowdesk.migration.LocalMigrationRunner;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Local development data only. Neither Spring startup nor Flyway migrate invokes this entry point. */
public final class LocalSeedRunner {
    private static final String ROLE = "flowdesk_migrator";
    private static final String A = "FlowDesk Demo A";
    private static final String B = "FlowDesk Demo B";
    private record User(String email, String name) { }
    private record Membership(String email, String workspace, String role) { }
    private record Fixture(User user, String workspace, String role) { }
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(new User("alice@example.test", "Alice"), A, "OWNER"),
            new Fixture(new User("bob@example.test", "Bob"), A, "ADMIN"),
            new Fixture(new User("carol@example.test", "Carol"), A, "MEMBER"),
            new Fixture(new User("dave@example.test", "Dave"), A, "VIEWER"),
            new Fixture(new User("eve@example.test", "Eve"), B, "OWNER"));
    private static final List<String> WORKSPACES = List.of(A, B);
    private record Snapshot(List<User> users, List<String> workspaces, List<Membership> memberships) {
        boolean empty() { return users.isEmpty() && workspaces.isEmpty() && memberships.isEmpty(); }
    }
    private static final Snapshot EXPECTED = new Snapshot(FIXTURES.stream().map(Fixture::user).toList(), WORKSPACES,
            FIXTURES.stream().map(f -> new Membership(f.user.email, f.workspace, f.role)).toList());

    public static void main(String[] args) {
        Logger.getLogger("").setLevel(Level.OFF);
        try {
            if (args.length != 1 || !Set.of("Apply", "Verify").contains(args[0])) {
                throw new IllegalArgumentException("configuration");
            }
            Map<String, String> env = System.getenv();
            if (env.containsKey("FLOWDESK_DB_PASSWORD") || env.containsKey("POSTGRES_PASSWORD")
                    || env.containsKey("FLOWDESK_MIGRATION_ADMIN_PASSWORD")) {
                throw new IllegalArgumentException("configuration");
            }
            String url = required(env, "FLOWDESK_MIGRATOR_DB_URL");
            String user = required(env, "FLOWDESK_MIGRATOR_DB_USERNAME");
            String password = required(env, "FLOWDESK_MIGRATOR_DB_PASSWORD");
            if (!ROLE.equals(user) || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/[a-z_][a-z0-9_]*")
                    || !password.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("configuration");
            Properties properties = new Properties();
            properties.setProperty("user", user);
            properties.setProperty("password", password);
            properties.setProperty("connectTimeout", "3");
            properties.setProperty("socketTimeout", "15");
            try (Connection connection = DriverManager.getConnection(url, properties)) {
                LocalMigrationRunner.verifyMigrator(connection);
                requireCurrent(connection);
                // Validation never calls migrate/repair and cannot supply missing schema/history.
                Flyway.configure().dataSource(url, user, password).defaultSchema("flowdesk").schemas("flowdesk")
                        .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false)
                        .jdbcProperties(Map.of("connectTimeout", "3", "socketTimeout", "15"))
                        .locations("classpath:db/migration").load().validate();
                int inserted = execute(connection, args[0].equals("Apply"));
                System.out.println(args[0].equals("Apply") ? "Seed rows inserted: " + inserted
                        : "Seed verification successful: users=5 workspaces=2 memberships=5.");
            }
        } catch (Exception failure) {
            System.err.println("Seed task failed (" + category(failure) + "). No automatic merge, reset or repair performed.");
            System.exit(1);
        }
    }

    private static String category(Exception error) {
        if (error instanceof SQLException sql) return "SQLState " + sql.getSQLState();
        if (error instanceof FlywayValidateException) return "validation/checksum";
        if (error instanceof IllegalArgumentException && Set.of("configuration", "mismatch", "schema/version").contains(error.getMessage())) {
            return error.getMessage();
        }
        return "database/migration";
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("configuration");
        return value;
    }

    private static void requireCurrent(Connection connection) throws SQLException {
        com.flowdesk.migration.MigrationHistory.requireCurrent(connection);
    }

    private static int execute(Connection connection, boolean apply) throws SQLException {
        connection.setReadOnly(!apply);
        connection.setTransactionIsolation(apply ? Connection.TRANSACTION_READ_COMMITTED : Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try {
            try (var st = connection.createStatement()) {
                st.execute("SET LOCAL lock_timeout='3s'");
                st.execute("SET LOCAL statement_timeout='10s'");
                if (apply) {
                    st.execute("LOCK TABLE flowdesk.users, flowdesk.workspaces, flowdesk.workspace_memberships, flowdesk.projects, flowdesk.tasks, flowdesk.task_status_activities IN SHARE ROW EXCLUSIVE MODE");
                    st.execute("LOCK TABLE flowdesk.flyway_schema_history IN SHARE MODE");
                }
            }
            LocalMigrationRunner.verifyMigrator(connection);
            requireCurrent(connection);
            try(var st=connection.createStatement();var rows=st.executeQuery("SELECT EXISTS(SELECT 1 FROM flowdesk.projects) OR EXISTS(SELECT 1 FROM flowdesk.tasks) OR EXISTS(SELECT 1 FROM flowdesk.task_status_activities)")) {
                rows.next();if(rows.getBoolean(1))throw new IllegalArgumentException("mismatch");
            }
            Snapshot current = snapshot(connection);
            int inserted = 0;
            if (apply && current.empty()) {
                insert(connection);
                inserted = 12;
            } else if (!EXPECTED.equals(current)) {
                throw new IllegalArgumentException("mismatch");
            }
            if (!EXPECTED.equals(snapshot(connection))) throw new IllegalArgumentException("mismatch");
            try (var st = connection.createStatement(); var rs = st.executeQuery("""
                    SELECT w.id FROM flowdesk.workspaces w LEFT JOIN flowdesk.workspace_memberships m
                      ON m.workspace_id=w.id AND m.role='OWNER'
                    GROUP BY w.id HAVING count(m.id)<>1
                    """)) {
                if (rs.next()) throw new IllegalArgumentException("mismatch");
            }
            connection.commit();
            return inserted;
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        }
    }

    private static Snapshot snapshot(Connection connection) throws SQLException {
        List<User> users = new ArrayList<>();
        List<String> workspaces = new ArrayList<>();
        List<Membership> memberships = new ArrayList<>();
        try (var st = connection.createStatement()) {
            try (var rs = st.executeQuery("SELECT email,display_name FROM flowdesk.users ORDER BY email")) {
                while (rs.next()) users.add(new User(rs.getString(1), rs.getString(2)));
            }
            try (var rs = st.executeQuery("SELECT name FROM flowdesk.workspaces ORDER BY name")) {
                while (rs.next()) workspaces.add(rs.getString(1));
            }
            try (var rs = st.executeQuery("""
                    SELECT u.email,w.name,m.role FROM flowdesk.workspace_memberships m
                    JOIN flowdesk.users u ON u.id=m.user_id JOIN flowdesk.workspaces w ON w.id=m.workspace_id
                    ORDER BY u.email,w.name
                    """)) {
                while (rs.next()) memberships.add(new Membership(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
            // Count physical memberships too: an unexpected FK drift must not hide orphan rows in the join.
            try (var rs = st.executeQuery("SELECT count(*) FROM flowdesk.workspace_memberships")) {
                rs.next();
                if (rs.getLong(1) != memberships.size()) throw new IllegalArgumentException("mismatch");
            }
        }
        return new Snapshot(users, workspaces, memberships);
    }

    private static void insert(Connection connection) throws SQLException {
        Map<String, Long> users = new HashMap<>();
        Map<String, Long> workspaces = new HashMap<>();
        for (Fixture fixture : FIXTURES) {
            users.put(fixture.user.email, insertId(connection,
                    "INSERT INTO flowdesk.users(email,display_name) VALUES (?,?) RETURNING id", fixture.user.email, fixture.user.name));
        }
        for (String workspace : WORKSPACES) {
            workspaces.put(workspace, insertId(connection, "INSERT INTO flowdesk.workspaces(name) VALUES (?) RETURNING id", workspace));
        }
        for (Fixture fixture : FIXTURES) {
            insertId(connection, "INSERT INTO flowdesk.workspace_memberships(user_id,workspace_id,role) VALUES (?,?,?) RETURNING id",
                    users.get(fixture.user.email), workspaces.get(fixture.workspace), fixture.role);
        }
    }

    private static long insertId(Connection connection, String sql, Object... values) throws SQLException {
        try (var st = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) st.setObject(i + 1, values[i]);
            try (var rs = st.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }
}
