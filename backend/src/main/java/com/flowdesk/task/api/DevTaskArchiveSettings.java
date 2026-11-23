package com.flowdesk.task.api;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

@Component
@Profile("dev-actor")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="flowdesk.dev.task-archive-enabled",havingValue="true")
public class DevTaskArchiveSettings {
    private final DataSource dataSource;
    public DevTaskArchiveSettings(DataSource dataSource) { this.dataSource=dataSource; }
    @PostConstruct void validate() {
        try(var c=dataSource.getConnection();var st=c.createStatement();var rs=st.executeQuery(
                "SELECT has_column_privilege(current_user,'flowdesk.tasks','archived_at','SELECT') AND has_column_privilege(current_user,'flowdesk.tasks','archived_at','UPDATE')")) {
            if(!rs.next()||!rs.getBoolean(1)) throw new IllegalStateException();
        } catch(Exception failure) {
            throw new IllegalStateException("Task archive access is not provisioned for this execution mode.");
        }
    }
}
