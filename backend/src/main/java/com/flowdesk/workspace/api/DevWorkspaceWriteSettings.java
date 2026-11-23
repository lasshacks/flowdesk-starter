package com.flowdesk.workspace.api;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
public class DevWorkspaceWriteSettings {
    private final Set<String> origins = new HashSet<>();
    private final Environment environment;
    private final DataSource dataSource;

    public DevWorkspaceWriteSettings(Environment environment, DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @PostConstruct
    void validate() {
        String configured = environment.getProperty("FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS", "");
        for (String origin : configured.split(",", -1)) {
            var match = Pattern.compile("http://127\\.0\\.0\\.1:([1-9][0-9]{0,4})").matcher(origin);
            if (!match.matches() || Integer.parseInt(match.group(1)) > 65535 || !origins.add(origin)) {
                throw new IllegalStateException("Development workspace writes require explicit loopback origins.");
            }
        }
        // Effective privileges only. Exact ACL/ownership checks remain in the dedicated admin tool.
        String check = "database privileges";
        try (var connection = dataSource.getConnection(); var st = connection.createStatement()) {
            boolean membershipManagement;
            try (var rows = st.executeQuery("SELECT has_table_privilege(current_user,'flowdesk.workspace_memberships','DELETE')")) {
                if (!rows.next()) throw new IllegalStateException();
                membershipManagement = rows.getBoolean(1);
            }
            try (var rows = st.executeQuery("""
                    SELECT current_user='flowdesk_app'
                      AND has_schema_privilege(current_user,'flowdesk','USAGE')
                      AND NOT has_schema_privilege(current_user,'flowdesk','CREATE')
                      AND NOT has_database_privilege(current_user,current_database(),'CREATE')
                      AND NOT has_database_privilege(current_user,current_database(),'TEMP')
                      AND NOT has_table_privilege(current_user,'flowdesk.flyway_schema_history','SELECT')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
            }
            for (String table : Set.of("users", "workspaces", "workspace_memberships", "projects", "tasks")) {
                check = table + " read/create privileges";
                if(table.equals("tasks")) {
                    try(var rows=st.executeQuery("""
                            SELECT NOT has_table_privilege(current_user,'flowdesk.tasks','SELECT,INSERT')
                              AND NOT has_table_privilege(current_user,'flowdesk.tasks','DELETE,TRUNCATE,REFERENCES,TRIGGER')
                              AND NOT has_any_column_privilege(current_user,'flowdesk.tasks','REFERENCES')
                              AND (SELECT count(*)=12 AND bool_and(attname IN ('id','project_id','title','description','status','priority','created_at','updated_at','version','assignee_id','due_date','archived_at'))
                                   FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped
                                     AND has_column_privilege(current_user,attrelid,attnum,'SELECT'))
                              AND (SELECT count(*)=8 AND bool_and(attname IN ('project_id','title','description','status','priority','version','assignee_id','due_date'))
                                   FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped
                                     AND has_column_privilege(current_user,attrelid,attnum,'INSERT'))
                              AND NOT has_sequence_privilege(current_user,'flowdesk.tasks_id_seq','USAGE,SELECT,UPDATE')
                            """)) { if(!rows.next()||!rows.getBoolean(1))throw new IllegalStateException(); }
                    continue;
                }
                String readCheck = switch (table) {
                    case "users" -> "(has_table_privilege(current_user,'flowdesk.users','SELECT') OR " + mappedReadCheck("users", "id,email,display_name,created_at,updated_at") + ")";
                    case "workspaces" -> "(has_table_privilege(current_user,'flowdesk.workspaces','SELECT') OR " + mappedReadCheck("workspaces", "id,name,created_at,updated_at,version") + ")";
                    case "workspace_memberships" -> "(has_table_privilege(current_user,'flowdesk.workspace_memberships','SELECT') OR " + mappedReadCheck("workspace_memberships", "id,user_id,workspace_id,role,created_at,updated_at") + ")";
                    default -> "has_table_privilege(current_user,'flowdesk." + table + "','SELECT')";
                };
                try (var rows = st.executeQuery("SELECT " + readCheck
                        + " AND has_table_privilege(current_user,'flowdesk." + table + "','INSERT')=" + !table.equals("users")
                        + " AND NOT has_table_privilege(current_user,'flowdesk." + table + "','UPDATE,TRUNCATE')"
                        + (table.equals("workspace_memberships") ? ""
                                : " AND NOT has_table_privilege(current_user,'flowdesk." + table + "','DELETE')")
                        + " AND NOT has_sequence_privilege(current_user,'flowdesk." + table + "_id_seq','USAGE,SELECT,UPDATE')")) {
                    if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
                }
            }
            try (var rows = st.executeQuery("""
                    SELECT count(*)=3 AND bool_and(attname IN ('name','updated_at','version'))
                    FROM pg_attribute WHERE attrelid='flowdesk.workspaces'::regclass AND attnum>0 AND NOT attisdropped
                      AND has_column_privilege(current_user,attrelid,attnum,'UPDATE')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
            }
            check = "Project update columns";
            try (var rows = st.executeQuery("""
                    SELECT (count(*)=4 AND bool_and(attname IN ('name','description','updated_at','version')))
                           OR (count(*)=5 AND bool_and(attname IN ('name','description','updated_at','version','archived_at'))
                               AND bool_or(attname='archived_at'))
                    FROM pg_attribute WHERE attrelid='flowdesk.projects'::regclass AND attnum>0 AND NOT attisdropped
                      AND has_column_privilege(current_user,attrelid,attnum,'UPDATE')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
            }
            check = "Task update columns";
            try (var rows = st.executeQuery("""
                    SELECT count(*) IN (8,9) AND bool_and(attname IN ('title','description','priority','updated_at','version','status','assignee_id','due_date','archived_at'))
                    FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped
                      AND has_column_privilege(current_user,attrelid,attnum,'UPDATE')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
            }
            check = "Task status-history insert columns";
            try (var rows = st.executeQuery("""
                    SELECT count(*)=6 AND bool_and(attname IN ('task_id','actor_user_id','from_status','to_status','task_version','occurred_at'))
                    FROM pg_attribute WHERE attrelid='flowdesk.task_status_activities'::regclass AND attnum>0 AND NOT attisdropped
                      AND has_column_privilege(current_user,attrelid,attnum,'INSERT')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
            }
            check = "Task status-history relation privileges";
            try (var rows = st.executeQuery("""
                    SELECT NOT has_table_privilege(current_user,'flowdesk.task_status_activities','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                      AND NOT has_any_column_privilege(current_user,'flowdesk.task_status_activities','UPDATE,REFERENCES')
                      AND NOT has_sequence_privilege(current_user,'flowdesk.task_status_activities_id_seq','USAGE,SELECT,UPDATE')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
            }
            for (String table : Set.of("users")) {
                try (var rows = st.executeQuery("SELECT NOT has_any_column_privilege(current_user,'flowdesk." + table + "','UPDATE')")) {
                    if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
                }
            }
            check = "Workspace membership-management columns";
            if (membershipManagement) {
                try (var rows = st.executeQuery("""
                        SELECT has_table_privilege(current_user,'flowdesk.workspace_memberships','DELETE')
                          AND (SELECT count(*)=2 AND bool_and(attname IN ('role','updated_at')) FROM pg_attribute
                                  WHERE attrelid='flowdesk.workspace_memberships'::regclass AND attnum>0 AND NOT attisdropped
                                    AND has_column_privilege(current_user,attrelid,attnum,'UPDATE'))
                        """)) {
                    if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
                }
                try (var rows = st.executeQuery("""
                    SELECT (SELECT count(*)=10 AND bool_and(attname IN ('id','workspace_id','target_user_id','invited_by_user_id','role','token_digest','status','created_at','expires_at','accepted_at'))
                              FROM pg_attribute WHERE attrelid='flowdesk.workspace_invitations'::regclass AND attnum>0 AND NOT attisdropped
                                AND has_column_privilege(current_user,attrelid,attnum,'SELECT'))
                      AND (SELECT count(*)=8 AND bool_and(attname IN ('workspace_id','target_user_id','invited_by_user_id','role','token_digest','status','expires_at','accepted_at'))
                              FROM pg_attribute WHERE attrelid='flowdesk.workspace_invitations'::regclass AND attnum>0 AND NOT attisdropped
                                AND has_column_privilege(current_user,attrelid,attnum,'INSERT'))
                      AND (SELECT count(*)=2 AND bool_and(attname IN ('status','accepted_at'))
                              FROM pg_attribute WHERE attrelid='flowdesk.workspace_invitations'::regclass AND attnum>0 AND NOT attisdropped
                                AND has_column_privilege(current_user,attrelid,attnum,'UPDATE'))
                      AND NOT has_table_privilege(current_user,'flowdesk.workspace_invitations','SELECT,INSERT,UPDATE,DELETE')
                    """)) {
                    if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
                }
            } else {
                try (var rows = st.executeQuery("""
                        SELECT NOT has_any_column_privilege(current_user,'flowdesk.workspace_memberships','UPDATE')
                          AND NOT has_table_privilege(current_user,'flowdesk.workspace_memberships','DELETE')
                          AND NOT has_table_privilege(current_user,'flowdesk.workspace_invitations','SELECT,INSERT,UPDATE,DELETE')
                          AND NOT has_any_column_privilege(current_user,'flowdesk.workspace_invitations','SELECT,INSERT,UPDATE')
                        """)) {
                    if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException();
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Development workspace writes require approved minimum privileges; failed check: " + check + ".");
        }
    }

    private static String mappedReadCheck(String table, String columns) {
        String[] names = columns.split(",");
        String quoted = java.util.Arrays.stream(names).map(name -> "'" + name + "'")
                .collect(java.util.stream.Collectors.joining(","));
        return "(SELECT count(*)=" + names.length + " AND bool_and(attname IN (" + quoted + "))"
                + " FROM pg_attribute WHERE attrelid='flowdesk." + table + "'::regclass"
                + " AND attname IN (" + quoted + ") AND attnum>0 AND NOT attisdropped"
                + " AND has_column_privilege(current_user,attrelid,attnum,'SELECT'))";
    }

    boolean allows(String origin) { return origins.contains(origin); }
}
