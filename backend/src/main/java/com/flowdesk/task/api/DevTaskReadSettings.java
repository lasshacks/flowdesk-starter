package com.flowdesk.task.api;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
@Component @Profile("dev-actor")
public class DevTaskReadSettings {
    private final DataSource dataSource;
    public DevTaskReadSettings(DataSource dataSource){this.dataSource=dataSource;}
    @PostConstruct void validate(){
        try(var c=dataSource.getConnection();var st=c.createStatement();var rs=st.executeQuery("""
                SELECT current_user='flowdesk_app' AND NOT has_table_privilege(current_user,'flowdesk.tasks','SELECT')
                  AND (SELECT count(*)=12 AND bool_and(attname IN ('id','project_id','title','description','status','priority','created_at','updated_at','version','assignee_id','due_date','archived_at'))
                       FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped
                         AND has_column_privilege(current_user,attrelid,attnum,'SELECT'))
                """)){
            if(!rs.next()||!rs.getBoolean(1))throw new IllegalStateException();
        }catch(Exception failure){throw new IllegalStateException("Development Task reads require approved Task SELECT access.");}
    }
}
