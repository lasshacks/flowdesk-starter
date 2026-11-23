package com.flowdesk.task.api;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

@Component
@Profile("dev-actor")
public class DevTaskStatusHistoryReadSettings {
    private final DataSource dataSource;
    public DevTaskStatusHistoryReadSettings(DataSource dataSource) { this.dataSource=dataSource; }
    @PostConstruct void validate() {
        // Effective checks at app startup; exact raw ACL/ownership is verified by the admin tool.
        try(var c=dataSource.getConnection();var st=c.createStatement()) {
            try(var rows=st.executeQuery("""
                    SELECT current_user='flowdesk_app'
                      AND NOT has_table_privilege(current_user,'flowdesk.task_status_activities','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                      AND NOT has_any_column_privilege(current_user,'flowdesk.task_status_activities','UPDATE,REFERENCES')
                      AND NOT has_column_privilege(current_user,'flowdesk.task_status_activities','id','INSERT')
                      AND NOT has_sequence_privilege(current_user,'flowdesk.task_status_activities_id_seq','USAGE,SELECT,UPDATE')
                    """)) {
                if(!rows.next()||!rows.getBoolean(1))throw new IllegalStateException();
            }
            try(var rows=st.executeQuery("""
                    SELECT count(*)=7 AND bool_and(attname IN ('id','task_id','actor_user_id','from_status','to_status','task_version','occurred_at'))
                    FROM pg_attribute WHERE attrelid='flowdesk.task_status_activities'::regclass AND attnum>0 AND NOT attisdropped
                      AND has_column_privilege(current_user,attrelid,attnum,'SELECT')
                    """)) {
                if(!rows.next()||!rows.getBoolean(1))throw new IllegalStateException();
            }
        } catch(Exception failure) {
            throw new IllegalStateException("Development Task status history reads require the approved Task-status-history-read policy.");
        }
    }
}
