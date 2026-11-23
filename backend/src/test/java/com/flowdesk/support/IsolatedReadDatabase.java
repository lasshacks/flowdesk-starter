package com.flowdesk.support;

import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
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

/** Fresh database per fixture; production runner processes receive in-memory test credentials only. */
public final class IsolatedReadDatabase {
    public static final String APP_PASSWORD = password();
    public static final String MIGRATOR_PASSWORD = password();
    public final String url;
    private final PostgreSQLContainer<?> postgres;

    public IsolatedReadDatabase(PostgreSQLContainer<?> postgres, boolean readAccess) throws Exception {
        this(postgres, readAccess, "11");
    }

    public IsolatedReadDatabase(PostgreSQLContainer<?> postgres, boolean readAccess, String target) throws Exception {
        this.postgres = postgres;
        String database = "read_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), "postgres", postgres.getPassword())) {
            admin.createStatement().execute("CREATE DATABASE " + database);
            try (var st = admin.prepareStatement("SELECT set_config('flowdesk.test_password',?,false)")) {
                st.setString(1, MIGRATOR_PASSWORD); st.execute();
            }
            admin.createStatement().execute("""
                    DO $role$ BEGIN
                      IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='flowdesk_migrator') THEN
                        EXECUTE format('CREATE ROLE flowdesk_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L',current_setting('flowdesk.test_password'));
                      END IF;
                    END $role$;
                    """);
        }
        url = "jdbc:postgresql://127.0.0.1:" + postgres.getMappedPort(5432) + "/" + database;
        try (var admin = admin()) {
            try (var st = admin.prepareStatement("SELECT set_config('flowdesk.app_password',?,false)")) {
                st.setString(1, APP_PASSWORD); st.execute();
            }
            admin.createStatement().execute(new ClassPathResource("db/app-role.sql").getContentAsString(StandardCharsets.UTF_8));
            admin.createStatement().execute("GRANT CONNECT ON DATABASE " + database + " TO flowdesk_migrator");
            admin.createStatement().execute("CREATE SCHEMA flowdesk AUTHORIZATION flowdesk_migrator");
        }
        Flyway.configure().dataSource(url, "flowdesk_migrator", MIGRATOR_PASSWORD)
                .defaultSchema("flowdesk").schemas("flowdesk").createSchemas(false)
                .baselineOnMigrate(false).cleanDisabled(true).target(target).load().migrate();
        if (target.equals("11")) seed("Apply").success();
        if (readAccess) grant().success();
    }

    public Result grant() throws Exception { return grant(Map.of()); }
    public Result grant(Map<String,String> overrides) throws Exception {
        return grant(overrides, false);
    }
    public Result grantCreate() throws Exception { return grant(Map.of(), true); }
    public Result grantCreate(Map<String,String> overrides) throws Exception { return grant(overrides, true); }
    public Result grantProjectUpdate() throws Exception { return grant(Map.of(), "projectUpdate"); }
    public Result grantProjectUpdate(Map<String,String> overrides) throws Exception { return grant(overrides, "projectUpdate"); }
    public Result grantProjectArchive() throws Exception { return grant(Map.of(), "projectArchive"); }
    public Result grantProjectArchive(Map<String,String> overrides) throws Exception { return grant(overrides, "projectArchive"); }
    public Result grantTaskStatusHistoryRead() throws Exception { return grant(Map.of(), "taskStatusHistoryRead"); }
    public Result grantTaskStatusHistoryRead(Map<String,String> overrides) throws Exception { return grant(overrides, "taskStatusHistoryRead"); }
    public Result grantTaskAssigneeDueDate() throws Exception { return grant(Map.of(), "taskAssigneeDueDate"); }
    public Result grantTaskAssigneeDueDate(Map<String,String> overrides) throws Exception { return grant(overrides, "taskAssigneeDueDate"); }
    public Result grantTaskArchive() throws Exception { return grant(Map.of(), "taskArchive"); }
    public Result grantTaskArchive(Map<String,String> overrides) throws Exception { return grant(overrides, "taskArchive"); }
    public Result grantWorkspaceMemberListRead() throws Exception { return grant(Map.of(), "workspaceMemberList"); }
    public Result grantWorkspaceMemberListRead(Map<String,String> overrides) throws Exception { return grant(overrides, "workspaceMemberList"); }
    public Result grantWorkspaceMembershipManagement() throws Exception { return grant(Map.of(), "workspaceMembershipManagement"); }
    public Result grantWorkspaceMembershipManagement(Map<String,String> overrides) throws Exception { return grant(overrides, "workspaceMembershipManagement"); }
    public Result grantTaskStatusChange() throws Exception { return grant(Map.of(), "taskStatusChange"); }
    public Result grantTaskStatusChange(Map<String,String> overrides) throws Exception { return grant(overrides, "taskStatusChange"); }
    public Result grantTaskUpdate() throws Exception { return grant(Map.of(), "taskUpdate"); }
    public Result grantTaskUpdate(Map<String,String> overrides) throws Exception { return grant(overrides, "taskUpdate"); }
    public Result grantTask() throws Exception { return grant(Map.of(), "task"); }
    public Result grantTask(Map<String,String> overrides) throws Exception { return grant(overrides, "task"); }
    public int migrateTo(String target) {
        return flyway(target).migrate().migrationsExecuted;
    }
    public void validateTo(String target) { flyway(target).validate(); }
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url,"flowdesk_migrator",MIGRATOR_PASSWORD)
                .defaultSchema("flowdesk").schemas("flowdesk").createSchemas(false)
                .cleanDisabled(true).baselineOnMigrate(false).target(target).load();
    }
    public Result grantProject() throws Exception { return grant(Map.of(), "project"); }
    public Result grantProject(Map<String,String> overrides) throws Exception { return grant(overrides, "project"); }
    public Result grantRename() throws Exception { return grantRename(Map.of()); }
    public Result grantRename(Map<String,String> overrides) throws Exception { return grant(overrides, "rename"); }
    private Result grant(Map<String,String> overrides, boolean creation) throws Exception { return grant(overrides, creation ? "create" : "read"); }
    private Result grant(Map<String,String> overrides, String action) throws Exception {
        var env = new java.util.HashMap<>(Map.of("FLOWDESK_MIGRATOR_DB_URL", url,
                "FLOWDESK_MIGRATION_ADMIN_USER", "postgres", "FLOWDESK_MIGRATION_ADMIN_PASSWORD", postgres.getPassword(),
                "FLOWDESK_READ_APP_PASSWORD", APP_PASSWORD));
        env.putAll(overrides);
        return run(action.equals("taskArchive") ? "com.flowdesk.migration.AppTaskArchiveAccessRunner" : action.equals("workspaceMembershipManagement") ? "com.flowdesk.migration.AppWorkspaceMembershipManagementAccessRunner" : action.equals("workspaceMemberList") ? "com.flowdesk.migration.AppWorkspaceMemberListAccessRunner" : action.equals("taskAssigneeDueDate") ? "com.flowdesk.migration.AppTaskAssigneeDueDateAccessRunner" : action.equals("taskStatusHistoryRead") ? "com.flowdesk.migration.AppTaskStatusHistoryReadAccessRunner" : action.equals("taskStatusChange") ? "com.flowdesk.migration.AppTaskStatusChangeAccessRunner" : action.equals("taskUpdate") ? "com.flowdesk.migration.AppTaskUpdateAccessRunner" : action.equals("task") ? "com.flowdesk.migration.AppTaskCreateAccessRunner" : action.equals("projectArchive") ? "com.flowdesk.migration.AppProjectArchiveAccessRunner" : action.equals("projectUpdate") ? "com.flowdesk.migration.AppProjectUpdateAccessRunner" : action.equals("project") ? "com.flowdesk.migration.AppProjectCreateAccessRunner" : action.equals("rename") ? "com.flowdesk.migration.AppWorkspaceRenameAccessRunner" : action.equals("create") ? "com.flowdesk.migration.AppWorkspaceCreateAccessRunner" : "com.flowdesk.migration.AppReadAccessRunner", "Apply", env);
    }
    public Result seed(String action) throws Exception { return run("com.flowdesk.seed.LocalSeedRunner", action, migratorEnv()); }
    public Result migrate(String action) throws Exception { return run("com.flowdesk.migration.LocalMigrationRunner", action, migratorEnv()); }
    private Map<String,String> migratorEnv() {
        return Map.of("FLOWDESK_MIGRATOR_DB_URL",url,"FLOWDESK_MIGRATOR_DB_USERNAME","flowdesk_migrator",
                "FLOWDESK_MIGRATOR_DB_PASSWORD",MIGRATOR_PASSWORD);
    }
    private Result run(String main, String action, Map<String,String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("flowdesk.seed.classpath"),main,action).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.matches("(?i)(FLOWDESK_.*|POSTGRES_.*|PGPASSWORD|FLYWAY_.*|SPRING_.*|DEBUG)"));
        builder.environment().putAll(env);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30,TimeUnit.SECONDS)).as("database runner completes").isTrue();
            String output = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            List<String> secrets = new ArrayList<>(List.of(APP_PASSWORD,MIGRATOR_PASSWORD,postgres.getPassword()));
            env.forEach((key,value) -> { if (key.contains("PASSWORD")) secrets.add(value); });
            for (String secret : secrets) if (!secret.isEmpty()) assertThat(output.contains(secret)).as("credential absent from output").isFalse();
            return new Result(process.exitValue(), output);
        } finally { if(process.isAlive()) process.destroyForcibly().waitFor(); }
    }
    public record Result(int code, String output) {
        public String success() { assertThat(code).as(output).isZero(); return output; }
        public String failure() { assertThat(code).as(output).isNotZero(); return output; }
    }
    public Connection admin() throws Exception { return DriverManager.getConnection(url,"postgres",postgres.getPassword()); }
    public Connection app() throws Exception { return DriverManager.getConnection(url,"flowdesk_app",APP_PASSWORD); }
    public void sql(String sql) throws Exception { try(var c=admin();var st=c.createStatement()){ st.execute(sql); } }
    public String query(String sql) throws Exception { try(var c=admin();var st=c.createStatement();var rs=st.executeQuery(sql)){rs.next();return rs.getString(1);} }
    public String snapshot() throws Exception {
        return query("SELECT coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.users x),'[]')||coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspaces x),'[]')||coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspace_memberships x),'[]')||coalesce((SELECT json_agg(x ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history x),'[]')");
    }
    public static String password(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
}
