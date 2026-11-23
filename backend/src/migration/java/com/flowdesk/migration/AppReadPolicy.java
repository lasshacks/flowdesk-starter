package com.flowdesk.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** Accepted local permission states. Never repairs or revokes unexpected privileges. */
public final class AppReadPolicy {
    public enum State { CONNECT_ONLY, READ_ONLY, WORKSPACE_CREATE, WORKSPACE_RENAME, PROJECT_CREATE, PROJECT_UPDATE, TASK_CREATE, TASK_UPDATE, TASK_STATUS_CHANGE, TASK_STATUS_HISTORY_READ, TASK_ASSIGNEE_DUE_DATE, WORKSPACE_MEMBERSHIP_MANAGEMENT, TASK_ARCHIVE }

    public static State verify(Connection connection) throws SQLException {
        require(connection, """
                SELECT rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls)
                  AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid OR roleid=r.oid)
                FROM pg_roles r WHERE rolname='flowdesk_app'
                """);
        require(connection, """
                SELECT has_database_privilege('flowdesk_app',current_database(),'CONNECT')
                  AND NOT has_database_privilege('flowdesk_app',current_database(),'CREATE')
                  AND NOT has_database_privilege('flowdesk_app',current_database(),'TEMP')
                  AND NOT has_schema_privilege('flowdesk_app','public','USAGE')
                  AND NOT has_schema_privilege('flowdesk_app','public','CREATE')
                """);
        require(connection, "SELECT pg_get_userbyid(nspowner)='flowdesk_migrator' FROM pg_namespace WHERE nspname='flowdesk'");
        zero(connection, """
                SELECT count(*) FROM pg_database d,
                  LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a
                WHERE d.datname=current_database() AND a.grantee<>d.datdba AND NOT
                  (a.grantee IN (SELECT oid FROM pg_roles WHERE rolname IN ('flowdesk_app','flowdesk_migrator'))
                   AND a.privilege_type='CONNECT' AND NOT a.is_grantable)
                """);
        zero(connection, """
                SELECT count(*) FROM pg_namespace n,
                  LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
                WHERE n.nspname='flowdesk' AND a.grantee<>n.nspowner AND NOT
                  (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                   AND a.privilege_type='USAGE' AND NOT a.is_grantable)
                """);
        zero(connection, """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND pg_get_userbyid(c.relowner)<>'flowdesk_migrator'
                """);
        zero(connection, """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace,
                  LATERAL aclexplode(coalesce(c.relacl,acldefault(
                    CASE WHEN c.relkind='S' THEN 's'::"char" ELSE 'r'::"char" END,c.relowner))) a
                WHERE n.nspname='flowdesk' AND c.relkind IN ('r','p','v','m','f','S')
                  AND a.grantee<>c.relowner AND NOT
                   (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                    AND c.relkind='r' AND c.relname IN ('users','workspaces','workspace_memberships','projects','tasks')
                    AND (a.privilege_type='SELECT' OR (a.privilege_type='INSERT'
                         AND c.relname IN ('workspaces','workspace_memberships','projects','tasks')))
                    AND NOT a.is_grantable)
                   AND NOT (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                    AND c.relkind='r' AND c.relname='workspace_memberships'
                    AND a.privilege_type='DELETE' AND NOT a.is_grantable)
                """);
        // Allow only explicitly approved column grants; counts and accepted states are checked below.
        zero(connection, """
                SELECT count(*) FROM pg_attribute t JOIN pg_class c ON c.oid=t.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace, LATERAL aclexplode(t.attacl) a
                WHERE n.nspname='flowdesk' AND a.grantee<>c.relowner AND NOT
                  (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                   AND c.relkind='r' AND ((c.relname='workspaces' AND t.attname IN ('name','updated_at','version'))
                     OR (c.relname='projects' AND t.attname IN ('name','description','updated_at','version','archived_at'))
                     OR (c.relname='tasks' AND t.attname IN ('title','description','priority','updated_at','version','status','assignee_id','due_date','archived_at'))
                     OR (c.relname='workspace_memberships' AND t.attname IN ('role','updated_at'))
                     OR (c.relname='workspace_invitations' AND t.attname IN ('status','accepted_at')))
                   AND a.privilege_type='UPDATE' AND NOT a.is_grantable AND NOT t.attisdropped)
                  AND NOT (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                    AND a.grantor=c.relowner AND c.relkind='r' AND c.relname='tasks'
                    AND t.attname IN ('id','project_id','title','description','status','priority','created_at','updated_at','version','assignee_id','due_date','archived_at')
                    AND a.privilege_type='SELECT' AND NOT a.is_grantable AND NOT t.attisdropped)
                  AND NOT (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                    AND c.relkind='r'
                    AND ((c.relname='users' AND t.attname IN ('id','email','display_name','created_at','updated_at'))
                      OR (c.relname='workspaces' AND t.attname IN ('id','name','created_at','updated_at','version'))
                      OR (c.relname='workspace_memberships' AND t.attname IN ('id','user_id','workspace_id','role','created_at','updated_at'))
                      OR (c.relname='workspace_invitations' AND t.attname IN ('id','workspace_id','target_user_id','invited_by_user_id','role','token_digest','status','created_at','expires_at','accepted_at')))
                    AND a.privilege_type='SELECT' AND NOT a.is_grantable AND NOT t.attisdropped)
                  AND NOT (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                    AND a.grantor=c.relowner AND c.relkind='r' AND c.relname='tasks'
                    AND t.attname IN ('project_id','title','description','status','priority','version','assignee_id','due_date')
                    AND a.privilege_type='INSERT' AND NOT a.is_grantable AND NOT t.attisdropped)
                  AND NOT (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                    AND c.relkind='r' AND c.relname='task_status_activities'
                    AND t.attname IN ('task_id','actor_user_id','from_status','to_status','task_version','occurred_at')
                    AND a.privilege_type='INSERT' AND NOT a.is_grantable AND NOT t.attisdropped)
                  AND NOT (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                    AND a.grantor=c.relowner AND c.relkind='r' AND c.relname='task_status_activities'
                    AND t.attname IN ('id','task_id','actor_user_id','from_status','to_status','task_version','occurred_at')
                    AND a.privilege_type='SELECT' AND NOT a.is_grantable AND NOT t.attisdropped)
                  AND NOT (a.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                    AND c.relkind='r' AND c.relname='workspace_invitations'
                    AND t.attname IN ('workspace_id','target_user_id','invited_by_user_id','role','token_digest','status','expires_at','accepted_at')
                    AND a.privilege_type='INSERT' AND NOT a.is_grantable AND NOT t.attisdropped)
                """);
        zero(connection, """
                SELECT count(*) FROM pg_default_acl d, LATERAL aclexplode(d.defaclacl) a
                WHERE d.defaclobjtype IN ('r','S') AND a.grantee<>d.defaclrole
                  AND (d.defaclnamespace=(SELECT oid FROM pg_namespace WHERE nspname='flowdesk')
                    OR (d.defaclnamespace=0 AND d.defaclrole=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_migrator')))
                """);
        boolean usage = truth(connection, "SELECT has_schema_privilege('flowdesk_app','flowdesk','USAGE')");
        long selects = count(connection, """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relkind='r'
                  AND c.relname IN ('users','workspaces','workspace_memberships')
                  AND has_table_privilege('flowdesk_app',c.oid,'SELECT')
                """);
        long baseColumnSelects = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace, LATERAL aclexplode(a.attacl) x
                WHERE n.nspname='flowdesk' AND c.relname IN ('users','workspaces','workspace_memberships')
                  AND x.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                  AND x.privilege_type='SELECT' AND NOT x.is_grantable AND a.attnum>0 AND NOT a.attisdropped
                """);
        long baseEffectiveColumnSelects = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname IN ('users','workspaces','workspace_memberships')
                  AND a.attnum>0 AND NOT a.attisdropped
                  AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'SELECT')
                """);
        boolean legacyReads = selects == 3 && baseColumnSelects == 0;
        boolean columnReads = selects == 0 && baseColumnSelects == 16 && baseEffectiveColumnSelects == 16;
        boolean reads = legacyReads || columnReads;
        long inserts = count(connection, """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relkind='r'
                  AND c.relname IN ('workspaces','workspace_memberships')
                  AND has_table_privilege('flowdesk_app',c.oid,'INSERT')
                """);
        long updates = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname='workspaces' AND a.attnum>0 AND NOT a.attisdropped
                  AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'UPDATE')
                """);
        long projectGrants = count(connection, """
                SELECT (has_table_privilege('flowdesk_app',c.oid,'SELECT')::int
                      + has_table_privilege('flowdesk_app',c.oid,'INSERT')::int)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname='projects' AND c.relkind='r'
                UNION ALL SELECT 0 WHERE to_regclass('flowdesk.projects') IS NULL
                """);
        long projectUpdates = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname='projects' AND a.attnum>0 AND NOT a.attisdropped
                  AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'UPDATE')
                """);
        long taskGrants = count(connection, """
                SELECT (has_table_privilege('flowdesk_app',c.oid,'SELECT')::int + has_table_privilege('flowdesk_app',c.oid,'INSERT')::int)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND c.relname='tasks' AND c.relkind='r'
                UNION ALL SELECT 0 WHERE to_regclass('flowdesk.tasks') IS NULL
                """);
        long taskUpdates = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname='tasks' AND a.attnum>0 AND NOT a.attisdropped
                  AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'UPDATE')
                """);
        boolean taskArchiveUpdate=hasTaskArchiveUpdate(connection);
        if(taskArchiveUpdate) taskUpdates--;
        long taskColumnSelects = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace, LATERAL aclexplode(a.attacl) x
                WHERE n.nspname='flowdesk' AND c.relname='tasks' AND a.attnum>0 AND NOT a.attisdropped
                  AND x.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                  AND x.privilege_type='SELECT' AND NOT x.is_grantable
                """);
        long taskColumnInserts = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace, LATERAL aclexplode(a.attacl) x
                WHERE n.nspname='flowdesk' AND c.relname='tasks' AND a.attnum>0 AND NOT a.attisdropped
                  AND x.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                  AND x.privilege_type='INSERT' AND NOT x.is_grantable
                """);
        boolean legacyTaskAccess=taskGrants==2&&taskColumnSelects==0&&taskColumnInserts==0;
        boolean assignedTaskAccess=taskGrants==0&&taskColumnSelects==12&&taskColumnInserts==8;
        long activityInserts = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname='task_status_activities' AND a.attnum>0 AND NOT a.attisdropped
                  AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'INSERT')
                """);
        long activitySelects = count(connection, """
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname='task_status_activities' AND a.attnum>0 AND NOT a.attisdropped
                  AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'SELECT')
                """);
        long invitationSelects = columnGrantCount(connection, "workspace_invitations", "SELECT");
        long invitationInserts = columnGrantCount(connection, "workspace_invitations", "INSERT");
        long invitationUpdates = columnGrantCount(connection, "workspace_invitations", "UPDATE");
        long membershipUpdates = columnGrantCount(connection, "workspace_memberships", "UPDATE");
        boolean membershipManagementPresent = truth(connection, "SELECT coalesce(has_table_privilege('flowdesk_app',to_regclass('flowdesk.workspace_memberships'),'DELETE'),false)")
                || invitationSelects != 0 || invitationInserts != 0 || invitationUpdates != 0 || membershipUpdates != 0;
        if (taskArchiveUpdate) {
            boolean latestTaskState=taskUpdates==8 && activitySelects==7 && activityInserts==6 && assignedTaskAccess
                    && projectUpdateColumnsValid(connection,projectUpdates) && projectGrants==2 && usage && reads && inserts==2 && updates==3;
            if(latestTaskState && membershipManagementPresent && invitationSelects==10 && invitationInserts==8 && invitationUpdates==2 && membershipUpdates==2
                    && truth(connection,"SELECT has_table_privilege('flowdesk_app','flowdesk.workspace_memberships','DELETE')")
                    && !truth(connection,"SELECT has_table_privilege('flowdesk_app','flowdesk.workspace_invitations','SELECT,INSERT,UPDATE,DELETE')")) return State.TASK_ARCHIVE;
            if(latestTaskState && !membershipManagementPresent) return State.TASK_ARCHIVE;
            throw new IllegalArgumentException("Task archive requires the complete existing Task access state and exactly one archive column UPDATE");
        }
        if (membershipManagementPresent) {
            if (taskUpdates == 8 && activitySelects == 7 && activityInserts == 6 && assignedTaskAccess
                    && projectUpdateColumnsValid(connection,projectUpdates) && projectGrants == 2 && usage && reads && inserts == 2 && updates == 3
                    && invitationSelects == 10 && invitationInserts == 8 && invitationUpdates == 2 && membershipUpdates == 2
                    && truth(connection, "SELECT has_table_privilege('flowdesk_app','flowdesk.workspace_memberships','DELETE')")
                    && !truth(connection, "SELECT has_table_privilege('flowdesk_app','flowdesk.workspace_invitations','SELECT,INSERT,UPDATE,DELETE')")) {
                return State.WORKSPACE_MEMBERSHIP_MANAGEMENT;
            }
            throw new IllegalArgumentException("Partial or excessive Workspace membership-management state");
        }
        if (activitySelects!=0) {
            if(activitySelects==7 && taskUpdates==8 && activityInserts==6 && assignedTaskAccess && projectUpdateColumnsValid(connection,projectUpdates) && projectGrants==2 && usage && reads && inserts==2 && updates==3) return State.TASK_ASSIGNEE_DUE_DATE;
            if(activitySelects==7 && taskUpdates==6 && activityInserts==6 && taskGrants==2 && projectUpdateColumnsValid(connection,projectUpdates) && projectGrants==2 && usage && reads && inserts==2 && updates==3) return State.TASK_STATUS_HISTORY_READ;
            throw new IllegalArgumentException("Partial or excessive Task status-history-read state");
        }
        if (taskUpdates==6 || activityInserts!=0) {
            if(taskUpdates==6 && activityInserts==6 && taskGrants==2 && projectUpdateColumnsValid(connection,projectUpdates) && projectGrants==2 && usage && reads && inserts==2 && updates==3) return State.TASK_STATUS_CHANGE;
            throw new IllegalArgumentException("Partial or excessive Task status-change state");
        }
        if (taskUpdates != 0) {
            if(taskUpdates==5 && legacyTaskAccess && projectUpdateColumnsValid(connection,projectUpdates) && projectGrants==2 && usage && reads && inserts==2 && updates==3) return State.TASK_UPDATE;
            throw new IllegalArgumentException("Partial or excessive Task update state");
        }
        if (taskGrants != 0) {
            if(legacyTaskAccess && projectUpdateColumnsValid(connection,projectUpdates) && projectGrants==2 && usage && reads && inserts==2 && updates==3) return State.TASK_CREATE;
            throw new IllegalArgumentException("Partial application Task privileges");
        }
        if (projectUpdates != 0) {
            if (projectUpdateColumnsValid(connection,projectUpdates) && projectGrants == 2 && usage && reads && inserts == 2 && updates == 3)
                return State.PROJECT_UPDATE;
            throw new IllegalArgumentException("Partial application Project update privileges");
        }
        if (projectGrants != 0) {
            if (projectGrants == 2 && usage && reads && inserts == 2 && updates == 3) return State.PROJECT_CREATE;
            throw new IllegalArgumentException("Partial application Project privileges");
        }
        if (updates != 0) {
            if (usage && reads && inserts == 2 && updates == 3) return State.WORKSPACE_RENAME;
            throw new IllegalArgumentException("Partial application rename privileges");
        }
        if (!usage && selects == 0 && baseColumnSelects == 0 && inserts == 0) return State.CONNECT_ONLY;
        if (usage && reads && inserts == 0) return State.READ_ONLY;
        if (usage && reads && inserts == 2) return State.WORKSPACE_CREATE;
        throw new IllegalArgumentException("Partial application read privileges");
    }

    private static void require(Connection c, String sql) throws SQLException {
        if (!truth(c, sql)) throw new IllegalArgumentException("Unexpected application access policy");
    }
    private static boolean truth(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) { return rs.next() && rs.getBoolean(1); }
    }
    private static long count(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
    private static long columnGrantCount(Connection c, String table, String privilege) throws SQLException {
        try (var st = c.prepareStatement("""
                SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace, LATERAL aclexplode(a.attacl) x
                WHERE n.nspname='flowdesk' AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped
                  AND x.grantee=(SELECT oid FROM pg_roles WHERE rolname='flowdesk_app')
                  AND x.privilege_type=? AND NOT x.is_grantable
                """)) {
            st.setString(1, table); st.setString(2, privilege);
            try (var rs=st.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }
    public static boolean hasProjectArchiveUpdate(Connection c) throws SQLException {
        return truth(c,"SELECT has_column_privilege('flowdesk_app','flowdesk.projects','archived_at','UPDATE')");
    }
    public static boolean hasTaskArchiveUpdate(Connection c) throws SQLException {
        if(!truth(c,"SELECT EXISTS(SELECT 1 FROM pg_attribute a JOIN pg_class t ON t.oid=a.attrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='flowdesk' AND t.relname='tasks' AND a.attname='archived_at' AND a.attnum>0 AND NOT a.attisdropped)")) return false;
        return truth(c,"SELECT has_column_privilege('flowdesk_app','flowdesk.tasks','archived_at','UPDATE')");
    }
    private static boolean projectUpdateColumnsValid(Connection c,long count) throws SQLException {
        if(count==4) return true;
        if(count!=5 || !hasProjectArchiveUpdate(c)) return false;
        return truth(c,"""
                SELECT count(*)=5 FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='flowdesk' AND c.relname='projects' AND a.attname IN ('name','description','updated_at','version','archived_at')
                  AND a.attnum>0 AND NOT a.attisdropped AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'UPDATE')
                """);
    }
    private static void zero(Connection c, String sql) throws SQLException {
        if (count(c, sql) != 0) throw new IllegalArgumentException("Unexpected application/schema grants");
    }
}
