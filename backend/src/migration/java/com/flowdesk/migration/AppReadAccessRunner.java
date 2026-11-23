package com.flowdesk.migration;

import org.flywaydb.core.Flyway;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Explicit administrator action. This class and policy resource never enter the app JAR. */
public final class AppReadAccessRunner {
    public static void main(String[] args) {
        run(args, false);
    }

    static void run(String[] args, boolean creation) { run(args, creation ? "create" : "read"); }

    static void run(String[] args, String action) {
        boolean taskStatusHistoryRead = action.equals("taskStatusHistoryRead");
        boolean taskAssigneeDueDate = action.equals("taskAssigneeDueDate");
        boolean taskArchive = action.equals("taskArchive");
        boolean workspaceMembershipManagement = action.equals("workspaceMembershipManagement");
        boolean workspaceMemberList = action.equals("workspaceMemberList");
        boolean taskStatusChange = action.equals("taskStatusChange");
        boolean taskUpdate = action.equals("taskUpdate");
        boolean task = action.equals("task");
        boolean projectUpdate = action.equals("projectUpdate");
        boolean projectArchive = action.equals("projectArchive");
        boolean project = action.equals("project");
        boolean creation = action.equals("create");
        boolean rename = action.equals("rename");
        Logger.getLogger("").setLevel(Level.OFF);
        try {
            if (args.length != 1 || !args[0].equals("Apply")) throw new IllegalArgumentException();
            Map<String, String> env = System.getenv();
            String url = required(env, "FLOWDESK_MIGRATOR_DB_URL");
            String adminUser = required(env, "FLOWDESK_MIGRATION_ADMIN_USER");
            String adminPassword = required(env, "FLOWDESK_MIGRATION_ADMIN_PASSWORD");
            String appPassword = required(env, "FLOWDESK_READ_APP_PASSWORD");
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/[a-z_][a-z0-9_]*")
                    || !adminUser.matches("[a-z_][a-z0-9_]*")
                    || Set.of("flowdesk_app", "flowdesk_migrator").contains(adminUser)
                    || !appPassword.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
            // Authenticate the existing app credential before any grants; never rotate it.
            try (Connection ignored = connect(url, "flowdesk_app", appPassword)) { }
            Flyway.configure().dataSource(url, adminUser, adminPassword).defaultSchema("flowdesk").schemas("flowdesk")
                    .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false)
                    .jdbcProperties(Map.of("connectTimeout", "3", "socketTimeout", "15"))
                    .locations("classpath:db/migration").load().validate();
            AppReadPolicy.State result;
            try (Connection admin = connect(url, adminUser, adminPassword)) {
                admin.setAutoCommit(false);
                try {
                    try (var st = admin.createStatement()) {
                        st.execute("SET LOCAL lock_timeout='3s'");
                        st.execute("SET LOCAL statement_timeout='10s'");
                        st.execute("SELECT pg_advisory_xact_lock(742910231)");
                        st.execute("LOCK TABLE flowdesk.users,flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.workspace_invitations,flowdesk.projects,flowdesk.tasks,flowdesk.task_status_activities,flowdesk.flyway_schema_history IN SHARE MODE");
                    }
                    var before = AppReadPolicy.verify(admin);
                    var baseBefore = normalizeState(before);
                    if (workspaceMembershipManagement && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE) throw new IllegalArgumentException();
                    if (workspaceMemberList && baseBefore == AppReadPolicy.State.CONNECT_ONLY) throw new IllegalArgumentException();
                    if (creation && baseBefore == AppReadPolicy.State.CONNECT_ONLY) throw new IllegalArgumentException();
                    if (rename && baseBefore != AppReadPolicy.State.WORKSPACE_CREATE
                            && baseBefore != AppReadPolicy.State.WORKSPACE_RENAME && baseBefore != AppReadPolicy.State.PROJECT_CREATE && baseBefore != AppReadPolicy.State.PROJECT_UPDATE && baseBefore != AppReadPolicy.State.TASK_CREATE && baseBefore != AppReadPolicy.State.TASK_UPDATE && baseBefore != AppReadPolicy.State.TASK_STATUS_CHANGE && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE) throw new IllegalArgumentException();
                    if (project && baseBefore != AppReadPolicy.State.WORKSPACE_RENAME && baseBefore != AppReadPolicy.State.PROJECT_CREATE && baseBefore != AppReadPolicy.State.PROJECT_UPDATE && baseBefore != AppReadPolicy.State.TASK_CREATE && baseBefore != AppReadPolicy.State.TASK_UPDATE && baseBefore != AppReadPolicy.State.TASK_STATUS_CHANGE && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE) throw new IllegalArgumentException();
                    if (projectUpdate && baseBefore != AppReadPolicy.State.PROJECT_CREATE && baseBefore != AppReadPolicy.State.PROJECT_UPDATE && baseBefore != AppReadPolicy.State.TASK_CREATE && baseBefore != AppReadPolicy.State.TASK_UPDATE && baseBefore != AppReadPolicy.State.TASK_STATUS_CHANGE && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)
                        throw new IllegalArgumentException();
                    if (projectArchive && baseBefore != AppReadPolicy.State.PROJECT_UPDATE && baseBefore != AppReadPolicy.State.TASK_CREATE && baseBefore != AppReadPolicy.State.TASK_UPDATE && baseBefore != AppReadPolicy.State.TASK_STATUS_CHANGE && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE) throw new IllegalArgumentException();
                    if(task && baseBefore != AppReadPolicy.State.PROJECT_UPDATE && baseBefore != AppReadPolicy.State.TASK_CREATE && baseBefore != AppReadPolicy.State.TASK_UPDATE && baseBefore != AppReadPolicy.State.TASK_STATUS_CHANGE && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskUpdate && baseBefore != AppReadPolicy.State.TASK_CREATE && baseBefore != AppReadPolicy.State.TASK_UPDATE && baseBefore != AppReadPolicy.State.TASK_STATUS_CHANGE && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskStatusChange && baseBefore != AppReadPolicy.State.TASK_UPDATE && baseBefore != AppReadPolicy.State.TASK_STATUS_CHANGE && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskStatusHistoryRead && baseBefore != AppReadPolicy.State.TASK_STATUS_CHANGE && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskAssigneeDueDate && baseBefore != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskArchive && baseBefore != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    MigrationHistory.requireCurrent(admin);
                    String policy = taskArchive ? "/db/policy/app-task-archive-access.sql" : projectArchive ? "/db/policy/app-project-archive-access.sql" : workspaceMembershipManagement ? "/db/policy/app-workspace-membership-management-access.sql" : workspaceMemberList ? "/db/policy/app-workspace-member-list-read-access.sql" : taskAssigneeDueDate ? "/db/policy/app-task-assignee-due-date-access.sql" : taskStatusHistoryRead ? "/db/policy/app-task-status-history-read-access.sql" : taskStatusChange ? "/db/policy/app-task-status-change-access.sql" : taskUpdate ? "/db/policy/app-task-update-access.sql" : task ? "/db/policy/app-task-create-access.sql" : projectUpdate ? "/db/policy/app-project-update-access.sql" : project ? "/db/policy/app-project-create-access.sql" : rename ? "/db/policy/app-workspace-rename-access.sql"
                            : creation ? "/db/policy/app-workspace-create-access.sql" : "/db/policy/app-read-access.sql";
                    try (var stream = AppReadAccessRunner.class.getResourceAsStream(policy)) {
                        if (stream == null) throw new IllegalArgumentException();
                        admin.createStatement().execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    result = AppReadPolicy.verify(admin);
                    var baseResult = normalizeState(result);
                    if (workspaceMembershipManagement && result != AppReadPolicy.State.WORKSPACE_MEMBERSHIP_MANAGEMENT
                            && result != AppReadPolicy.State.TASK_ARCHIVE) throw new IllegalArgumentException();
                    if (workspaceMemberList && !columnReadState(admin)) throw new IllegalArgumentException();
                    if(taskAssigneeDueDate && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskArchive && (result!=AppReadPolicy.State.TASK_ARCHIVE || !AppReadPolicy.hasTaskArchiveUpdate(admin)))throw new IllegalArgumentException();
                    if (projectArchive && (!AppReadPolicy.hasProjectArchiveUpdate(admin) || baseResult != AppReadPolicy.State.PROJECT_UPDATE && baseResult != AppReadPolicy.State.TASK_CREATE && baseResult != AppReadPolicy.State.TASK_UPDATE && baseResult != AppReadPolicy.State.TASK_STATUS_CHANGE && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)) throw new IllegalArgumentException();
                    if (projectUpdate && baseResult != AppReadPolicy.State.PROJECT_UPDATE && baseResult != AppReadPolicy.State.TASK_CREATE && baseResult != AppReadPolicy.State.TASK_UPDATE && baseResult != AppReadPolicy.State.TASK_STATUS_CHANGE && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE) throw new IllegalArgumentException();
                    if (project && baseResult != AppReadPolicy.State.PROJECT_CREATE && baseResult != AppReadPolicy.State.PROJECT_UPDATE && baseResult != AppReadPolicy.State.TASK_CREATE && baseResult != AppReadPolicy.State.TASK_UPDATE && baseResult != AppReadPolicy.State.TASK_STATUS_CHANGE && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE) throw new IllegalArgumentException();
                    if (rename && baseResult != AppReadPolicy.State.WORKSPACE_RENAME && baseResult != AppReadPolicy.State.PROJECT_CREATE && baseResult != AppReadPolicy.State.PROJECT_UPDATE && baseResult != AppReadPolicy.State.TASK_CREATE && baseResult != AppReadPolicy.State.TASK_UPDATE && baseResult != AppReadPolicy.State.TASK_STATUS_CHANGE && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE) throw new IllegalArgumentException();
                    if (creation && baseResult != AppReadPolicy.State.WORKSPACE_CREATE
                            && baseResult != AppReadPolicy.State.WORKSPACE_RENAME && baseResult != AppReadPolicy.State.PROJECT_CREATE && baseResult != AppReadPolicy.State.PROJECT_UPDATE && baseResult != AppReadPolicy.State.TASK_CREATE && baseResult != AppReadPolicy.State.TASK_UPDATE && baseResult != AppReadPolicy.State.TASK_STATUS_CHANGE && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE) throw new IllegalArgumentException();
                    if(task && baseResult != AppReadPolicy.State.TASK_CREATE && baseResult != AppReadPolicy.State.TASK_UPDATE && baseResult != AppReadPolicy.State.TASK_STATUS_CHANGE && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskUpdate && baseResult != AppReadPolicy.State.TASK_UPDATE && baseResult != AppReadPolicy.State.TASK_STATUS_CHANGE && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if (result == AppReadPolicy.State.CONNECT_ONLY) throw new IllegalArgumentException();
                    if(taskStatusChange && baseResult != AppReadPolicy.State.TASK_STATUS_CHANGE && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskStatusHistoryRead && baseResult != AppReadPolicy.State.TASK_STATUS_HISTORY_READ && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    if(taskAssigneeDueDate && baseResult != AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE)throw new IllegalArgumentException();
                    admin.commit();
                } catch (Exception failure) { admin.rollback(); throw failure; }
            }
            System.out.println("Application " + (taskArchive ? "task-archive" : projectArchive ? "project-archive" : workspaceMembershipManagement ? "workspace-membership-management" : workspaceMemberList ? "workspace-member-list-read" : taskAssigneeDueDate ? "task-assignee-due-date" : taskStatusHistoryRead ? "task-status-history-read" : taskStatusChange ? "task-status-change" : taskUpdate ? "task-update" : task ? "task-create" : projectUpdate ? "project-update" : project ? "project-create" : rename ? "workspace-rename" : creation ? "workspace-create" : "read") + " access: "
                    + result + "; existing password preserved.");
        } catch (Exception failure) {
            System.err.println("Application-access task failed. Check target, credentials, migration history and permission state; no automatic repair performed.");
            System.exit(1);
        }
    }
    private static AppReadPolicy.State normalizeState(AppReadPolicy.State state) {
        return state==AppReadPolicy.State.WORKSPACE_MEMBERSHIP_MANAGEMENT || state==AppReadPolicy.State.TASK_ARCHIVE
                ? AppReadPolicy.State.TASK_ASSIGNEE_DUE_DATE : state;
    }
    private static boolean columnReadState(Connection c) throws Exception {
        try (var st = c.createStatement(); var rs = st.executeQuery("""
                SELECT count(*)=16
                  AND NOT bool_or(has_table_privilege('flowdesk_app',t.relid,'SELECT'))
                  AND bool_and(has_column_privilege('flowdesk_app',t.relid,t.attnum,'SELECT'))
                FROM (
                  SELECT c.oid relid,a.attnum FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    JOIN pg_attribute a ON a.attrelid=c.oid
                  WHERE n.nspname='flowdesk' AND ((c.relname='users' AND a.attname IN ('id','email','display_name','created_at','updated_at'))
                    OR (c.relname='workspaces' AND a.attname IN ('id','name','created_at','updated_at','version'))
                    OR (c.relname='workspace_memberships' AND a.attname IN ('id','user_id','workspace_id','role','created_at','updated_at')))
                    AND a.attnum>0 AND NOT a.attisdropped
                ) t
                """)) { return rs.next() && rs.getBoolean(1); }
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
    private static Connection connect(String url, String user, String password) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "15");
        return DriverManager.getConnection(url, properties);
    }
}
