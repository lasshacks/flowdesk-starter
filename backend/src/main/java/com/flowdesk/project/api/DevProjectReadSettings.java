package com.flowdesk.project.api;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
@Component @Profile("dev-actor")
public class DevProjectReadSettings {
    private final DataSource dataSource;
    public DevProjectReadSettings(DataSource dataSource){this.dataSource=dataSource;}
    @PostConstruct void validate(){
        try(var c=dataSource.getConnection();var st=c.createStatement();var rs=st.executeQuery("SELECT current_user='flowdesk_app' AND has_table_privilege(current_user,'flowdesk.projects','SELECT')")){
            if(!rs.next()||!rs.getBoolean(1))throw new IllegalStateException();
        }catch(Exception failure){throw new IllegalStateException("Development Project reads require approved Project SELECT access.");}
    }
}
