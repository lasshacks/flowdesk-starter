package com.flowdesk.migration;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Explicit process only: no Spring context and no application credential fallback. */
public final class LocalMigrationRunner {
    private static final String ROLE = "flowdesk_migrator";

    public static void main(String[] args) {
        // Flyway/JDBC errors can contain SQL or connection configuration. Print our own bounded diagnostics.
        Logger.getLogger("").setLevel(Level.OFF);
        try {
            if (args.length != 1 || !Set.of("Info", "Validate", "Migrate", "Provision", "Inspect").contains(args[0])) {
                throw new IllegalArgumentException("Invalid action");
            }
            Map<String, String> env = System.getenv();
            if (Set.of("Info", "Validate", "Migrate").contains(args[0])
                    && (env.containsKey("FLOWDESK_DB_PASSWORD") || env.containsKey("POSTGRES_PASSWORD")
                    || env.containsKey("FLOWDESK_MIGRATION_ADMIN_PASSWORD"))) {
                throw new IllegalArgumentException("Unexpected application/admin environment");
            }
            String url = required(env, "FLOWDESK_MIGRATOR_DB_URL");
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/[a-z_][a-z0-9_]*")
                    || !ROLE.equals(required(env, "FLOWDESK_MIGRATOR_DB_USERNAME"))) {
                throw new IllegalArgumentException("Invalid local target");
            }
            if (args[0].equals("Inspect")) {
                try (Connection admin = connect(url, required(env, "FLOWDESK_MIGRATION_ADMIN_USER"),
                        required(env, "FLOWDESK_MIGRATION_ADMIN_PASSWORD"))) {
                    boolean exists = roleExists(admin);
                    if (exists) checkRole(admin);
                    checkSchemaOwner(admin, false);
                    System.out.println(exists ? "Migrator role: present" : "Migrator role: absent");
                }
                return;
            }
            String password = required(env, "FLOWDESK_MIGRATOR_DB_PASSWORD");
            if (!password.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid credentials");
            if (args[0].equals("Provision")) {
                provision(url, password, env);
            } else {
                migrate(url, password, args[0]);
            }
        } catch (Exception failure) {
            System.err.println("Migration task failed (" + category(failure)
                    + "). Check target, credentials, ownership and migration history; no automatic repair performed.");
            System.exit(1);
        }
    }

    private static String category(Exception error) {
        if (error instanceof SQLException sql) return "SQLState " + sql.getSQLState();
        if (error instanceof IllegalArgumentException) return "configuration";
        if (error instanceof org.flywaydb.core.api.exception.FlywayValidateException) return "validation/checksum";
        return "database/migration";
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing configuration");
        return value;
    }

    private static Connection connect(String url, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "10");
        return DriverManager.getConnection(url, properties);
    }

    private static void provision(String url, String password, Map<String, String> env) throws Exception {
        String adminUser = required(env, "FLOWDESK_MIGRATION_ADMIN_USER");
        if (!adminUser.matches("[a-z_][a-z0-9_]*") || adminUser.equals(ROLE) || adminUser.equals("flowdesk_app")) {
            throw new IllegalArgumentException("Invalid administrator");
        }
        try (Connection admin = connect(url, adminUser, required(env, "FLOWDESK_MIGRATION_ADMIN_PASSWORD"))) {
            admin.setAutoCommit(false);
            try {
                try (var st = admin.createStatement()) {
                    // Serializes this bootstrap across concurrent invocations in this database.
                    st.execute("SELECT pg_advisory_xact_lock(742910231)");
                }
                boolean exists = roleExists(admin);
                if (!exists && !"true".equals(env.get("FLOWDESK_MIGRATION_ALLOW_CREATE"))) {
                    throw new IllegalArgumentException("Preflight state changed");
                }
                checkSchemaOwner(admin, false);
                if (exists) {
                    checkRole(admin);
                    // Existing credentials must authenticate; never silently rotate a password.
                    try (Connection ignored = connect(url, ROLE, password)) { }
                } else {
                    try (var st = admin.prepareStatement("SELECT set_config('flowdesk.bootstrap_password', ?, true)")) {
                        st.setString(1, password);
                        st.execute();
                    }
                    try (var st = admin.createStatement()) {
                        st.execute("""
                            DO $bootstrap$ BEGIN
                              EXECUTE format('CREATE ROLE flowdesk_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L', current_setting('flowdesk.bootstrap_password'));
                            END $bootstrap$;
                            """);
                    }
                }
                try (var st = admin.createStatement()) {
                    // PUBLIC defaults would otherwise give this role TEMP and public-schema access.
                    // Refuse broader defaults instead of changing permissions of other accounts here.
                    checkRole(admin);
                    try (var rs = st.executeQuery("SELECT has_database_privilege('flowdesk_migrator', current_database(), 'CREATE') OR has_database_privilege('flowdesk_migrator', current_database(), 'TEMP') OR has_schema_privilege('flowdesk_migrator', 'public', 'CREATE') OR has_schema_privilege('flowdesk_migrator', 'public', 'USAGE')")) {
                        rs.next();
                        if (rs.getBoolean(1)) throw new IllegalArgumentException("Apply existing app-role policy first");
                    }
                    st.execute("DO $grant$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO flowdesk_migrator', current_database()); END $grant$");
                    if (!checkSchemaOwner(admin, false)) st.execute("CREATE SCHEMA flowdesk AUTHORIZATION flowdesk_migrator");
                }
                admin.commit();
            } catch (Exception failure) {
                admin.rollback();
                throw failure;
            }
        }
        // Verify the committed role can authenticate and has the expected boundary.
        try (Connection connection = connect(url, ROLE, password)) { verifyMigrator(connection); }
        System.out.println("Provisioned: flowdesk_migrator / flowdesk; existing passwords preserved.");
    }

    private static boolean roleExists(Connection connection) throws SQLException {
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname='flowdesk_migrator'")) {
            return rs.next();
        }
    }

    private static void checkRole(Connection connection) throws SQLException {
        try (var st = connection.createStatement(); var rs = st.executeQuery("""
                SELECT rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls)
                  AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid)
                FROM pg_roles WHERE rolname='flowdesk_migrator'
                """)) {
            if (!rs.next() || !rs.getBoolean(1)) throw new IllegalArgumentException("Unexpected role attributes");
        }
    }

    private static boolean checkSchemaOwner(Connection connection, boolean required) throws SQLException {
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT pg_get_userbyid(nspowner), nspacl FROM pg_namespace WHERE nspname='flowdesk'")) {
            if (!rs.next()) {
                if (required) throw new IllegalArgumentException("Missing schema");
                return false;
            }
            if (!ROLE.equals(rs.getString(1))) throw new IllegalArgumentException("Unexpected schema owner");
        }
        AppReadPolicy.verify(connection);
        return true;
    }

    public static void verifyMigrator(Connection connection) throws SQLException {
        checkRole(connection);
        checkSchemaOwner(connection, true);
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT current_user, has_database_privilege(current_database(), 'CREATE'), has_database_privilege(current_database(), 'TEMP')")) {
            rs.next();
            if (!ROLE.equals(rs.getString(1)) || rs.getBoolean(2) || rs.getBoolean(3)) throw new IllegalArgumentException("Unexpected database privileges");
        }
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND c.relowner<>(SELECT oid FROM pg_roles WHERE rolname='flowdesk_migrator')")) {
            rs.next();
            if (rs.getInt(1) != 0) throw new IllegalArgumentException("Unexpected object owner");
        }
        try (var st = connection.createStatement(); var rs = st.executeQuery("SELECT to_regclass('flowdesk.flyway_schema_history') IS NULL AND EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk')")) {
            rs.next();
            if (rs.getBoolean(1)) throw new IllegalArgumentException("Nonempty schema without migration history");
        }
    }

    private static void migrate(String url, String password, String action) throws Exception {
        try (Connection connection = connect(url, ROLE, password)) { verifyMigrator(connection); }
        Flyway flyway = Flyway.configure().dataSource(url, ROLE, password)
                .defaultSchema("flowdesk").schemas("flowdesk").createSchemas(false)
                .cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true)
                .locations("classpath:db/migration").load();
        switch (action) {
            case "Info" -> {
                for (var migration : flyway.info().all()) {
                    if (!Set.of("PENDING", "SUCCESS").contains(migration.getState().name())) {
                        throw new IllegalArgumentException("Unexpected migration history state");
                    }
                    System.out.println("Version " + migration.getVersion() + ": " + migration.getState());
                }
            }
            case "Validate" -> { flyway.validate(); System.out.println("Validation successful."); }
            case "Migrate" -> System.out.println("Migrations executed: " + flyway.migrate().migrationsExecuted);
            default -> throw new IllegalArgumentException("Invalid action");
        }
    }
}
