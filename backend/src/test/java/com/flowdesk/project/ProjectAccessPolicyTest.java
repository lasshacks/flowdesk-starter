package com.flowdesk.project;
import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class ProjectAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("project_policy").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    IsolatedReadDatabase base()throws Exception{
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantCreate().success();db.grantRename().success();return db;
    }
    @Test void exactGrantsRealIdentityInsertAndOldToolsPreserve()throws Exception{
        var db=base();assertThat(db.grantProject().success()).contains("PROJECT_CREATE");
        String acl=db.query("SELECT relacl::text FROM pg_class WHERE oid='flowdesk.projects'::regclass");
        assertThat(db.grantProject().success()).contains("PROJECT_CREATE");
        assertThat(db.grant().success()).contains("PROJECT_CREATE");assertThat(db.grantCreate().success()).contains("PROJECT_CREATE");assertThat(db.grantRename().success()).contains("PROJECT_CREATE");
        db.migrate("Validate").success();assertThat(db.migrate("Migrate").success()).contains("Migrations executed: 0");
        assertThat(db.query("SELECT relacl::text FROM pg_class WHERE oid='flowdesk.projects'::regclass")).isEqualTo(acl);
        try(var c=db.app();var st=c.createStatement();var rows=st.executeQuery("INSERT INTO flowdesk.projects(workspace_id,name) VALUES(1,'Real app') RETURNING id")){
            assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isPositive();
        }
        String before=db.snapshot(), projects=db.query("SELECT json_agg(p)::text FROM flowdesk.projects p");
        assertThat(db.seed("Apply").failure()).contains("mismatch");assertThat(db.seed("Verify").failure()).contains("mismatch");
        assertThat(db.snapshot()).isEqualTo(before);assertThat(db.query("SELECT json_agg(p)::text FROM flowdesk.projects p")).isEqualTo(projects);
        for(String sql:new String[]{"UPDATE flowdesk.projects SET name='no'","DELETE FROM flowdesk.projects","TRUNCATE flowdesk.projects","SELECT nextval('flowdesk.projects_id_seq')","SELECT * FROM flowdesk.flyway_schema_history","CREATE TABLE flowdesk.forbidden(id int)"}){
            assertThatThrownBy(()->{try(var c=db.app();var st=c.createStatement()){st.execute(sql);}}).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"GRANT SELECT ON flowdesk.projects TO flowdesk_app","GRANT INSERT ON flowdesk.projects TO flowdesk_app",
            "GRANT SELECT ON flowdesk.projects TO PUBLIC","GRANT UPDATE(name) ON flowdesk.projects TO flowdesk_app",
            "GRANT SELECT,INSERT ON flowdesk.projects TO flowdesk_app WITH GRANT OPTION","GRANT ALL ON flowdesk.projects TO flowdesk_app"})
    void partialOrExcessAclFailsWithoutRepair(String bad)throws Exception{
        var db=base();db.sql(bad);String acl=db.query("SELECT coalesce(relacl::text,'') FROM pg_class WHERE oid='flowdesk.projects'::regclass");
        db.grantProject().failure();db.grant().failure();db.migrate("Validate").failure();
        assertThat(db.query("SELECT coalesce(relacl::text,'') FROM pg_class WHERE oid='flowdesk.projects'::regclass")).isEqualTo(acl);
    }
    @Test void requiresRenameAndCorrectExistingCredential()throws Exception{
        var db=new IsolatedReadDatabase(POSTGRES,true);db.grantProject().failure();db.grantCreate().success();db.grantProject().failure();db.grantRename().success();
        db.grantProject(Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64))).failure();
        assertThat(db.query("SELECT has_table_privilege('flowdesk_app','flowdesk.projects','SELECT,INSERT')")).isEqualTo("f");
        db.grantProject().success();
    }
}
