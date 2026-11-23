package com.flowdesk.project.api;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@Profile("dev-actor")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="flowdesk.dev.project-archive-enabled",havingValue="true")
public class DevProjectArchiveSettings {
    private final DataSource dataSource;
    public DevProjectArchiveSettings(DataSource dataSource) {
        this.dataSource=dataSource;
    }
    @PostConstruct void validate() {
        try(var c=dataSource.getConnection();var st=c.createStatement();var rs=st.executeQuery(
                "SELECT has_column_privilege(current_user,'flowdesk.projects','archived_at','SELECT') AND has_column_privilege(current_user,'flowdesk.projects','archived_at','UPDATE')")) {
            if(!rs.next()||!rs.getBoolean(1)) throw new IllegalStateException();
        } catch(Exception failure) {
            throw new IllegalStateException("Project archive access is not provisioned for this execution mode.");
        }
    }
}
