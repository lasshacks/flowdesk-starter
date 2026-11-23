package com.flowdesk.migration;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class V7MigrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("v7_test").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    @Test void populatedV6UpgradePreservesRowsHistoryAclAndExistingSequences() throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,false,"6");
        db.sql("INSERT INTO flowdesk.users(email,display_name) VALUES('before@example.test','Before'); INSERT INTO flowdesk.workspaces(name,version) VALUES('Parent',7); INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role) VALUES(1,1,'OWNER'); INSERT INTO flowdesk.projects(workspace_id,name,version) VALUES(1,'Existing',9); INSERT INTO flowdesk.tasks(project_id,title,description,status,priority,version) VALUES(1,'Task','Before V7','IN_PROGRESS','HIGH',11)");
        db.sql("GRANT USAGE ON SCHEMA flowdesk TO flowdesk_app; GRANT SELECT ON flowdesk.users,flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.projects,flowdesk.tasks TO flowdesk_app; GRANT INSERT ON flowdesk.workspaces,flowdesk.workspace_memberships,flowdesk.projects,flowdesk.tasks TO flowdesk_app; GRANT UPDATE(name,updated_at,version) ON flowdesk.workspaces TO flowdesk_app; GRANT UPDATE(name,description,updated_at,version) ON flowdesk.projects TO flowdesk_app; GRANT UPDATE(title,description,priority,updated_at,version) ON flowdesk.tasks TO flowdesk_app");
        String rows=rows(db),history=oldHistory(db),acl=acl(db),seq=sequences(db);
        assertThat(db.migrate("Migrate").success()).contains("Migrations executed: 5");db.migrate("Validate").success();
        assertThat(db.migrate("Migrate").success()).contains("Migrations executed: 0");
        assertThat(rows(db)).isEqualTo(rows);assertThat(oldHistory(db)).isEqualTo(history);assertThat(acl(db)).isEqualTo(acl);assertThat(sequences(db)).isEqualTo(seq);
        assertThat(db.query("SELECT count(*) FROM flowdesk.task_status_activities")).isEqualTo("0");
        assertThat(db.query("SELECT string_agg(version,',' ORDER BY installed_rank) FROM flowdesk.flyway_schema_history WHERE success")).isEqualTo("1,2,3,4,5,6,7,8,9,10,11");
        assertThat(db.query("SELECT pg_get_userbyid(relowner)='flowdesk_migrator' AND NOT has_table_privilege('flowdesk_app',oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE') FROM pg_class WHERE oid='flowdesk.task_status_activities'::regclass")).isEqualTo("t");
        db.grantTaskStatusChange().success();assertThat(rows(db)).isEqualTo(rows);
        String prefix="INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at) VALUES";
        for(String values:new String[]{"(999,1,'TODO','DONE',1,CURRENT_TIMESTAMP)","(1,999,'TODO','DONE',1,CURRENT_TIMESTAMP)","(1,1,'TODO','TODO',1,CURRENT_TIMESTAMP)","(1,1,'UNKNOWN','DONE',1,CURRENT_TIMESTAMP)","(1,1,'TODO','UNKNOWN',1,CURRENT_TIMESTAMP)","(1,1,'TODO','DONE',0,CURRENT_TIMESTAMP)","(1,1,'TODO','DONE',NULL,CURRENT_TIMESTAMP)","(1,1,'TODO','DONE',1,NULL)"})
            assertThatThrownBy(()->db.sql(prefix+values)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).startsWith("23"));
        db.sql(prefix+"(1,1,'TODO','DONE',1,CURRENT_TIMESTAMP)");
        assertThatThrownBy(()->db.sql(prefix+"(1,1,'DONE','TODO',1,CURRENT_TIMESTAMP)")).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23505"));
        for(String sql:new String[]{"DELETE FROM flowdesk.tasks WHERE id=1","DELETE FROM flowdesk.users WHERE id=1"})
            assertThatThrownBy(()->db.sql(sql)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("23503"));
        db.sql("UPDATE flowdesk.tasks SET version=12 WHERE id=1");assertThat(db.query("SELECT task_version FROM flowdesk.task_status_activities")).isEqualTo("1");
        db.seed("Apply").failure();db.seed("Verify").failure();
    }
    private String rows(IsolatedReadDatabase db)throws Exception{return db.query("SELECT (SELECT json_agg(x ORDER BY id)::text FROM flowdesk.users x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspaces x)||(SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspace_memberships x)||(SELECT json_agg(json_build_object('id',id,'workspace_id',workspace_id,'name',name,'description',description,'created_at',created_at,'updated_at',updated_at,'version',version) ORDER BY id)::text FROM flowdesk.projects x)||(SELECT json_agg(json_build_object('id',id,'project_id',project_id,'title',title,'description',description,'status',status,'priority',priority,'created_at',created_at,'updated_at',updated_at,'version',version) ORDER BY id)::text FROM flowdesk.tasks x)");}
    private String oldHistory(IsolatedReadDatabase db)throws Exception{return db.query("SELECT json_agg(x ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history x WHERE version IN ('1','2','3','4','5','6')");}
    private String acl(IsolatedReadDatabase db)throws Exception{return db.query("SELECT (SELECT json_agg(x ORDER BY relname)::text FROM (SELECT relname,relacl::text,pg_get_userbyid(relowner) FROM pg_class WHERE relnamespace='flowdesk'::regnamespace AND relname NOT LIKE '%task_status_activities%' AND relname NOT LIKE '%workspace_invitations%') x)||coalesce((SELECT json_agg(x ORDER BY attrelid,attnum)::text FROM (SELECT attrelid,attnum,attacl::text FROM pg_attribute WHERE attrelid IN (SELECT oid FROM pg_class WHERE relnamespace='flowdesk'::regnamespace AND relname NOT LIKE '%task_status_activities%' AND relname NOT LIKE '%workspace_invitations%') AND attacl IS NOT NULL) x),'[]')");}
    private String sequences(IsolatedReadDatabase db)throws Exception{return db.query("SELECT json_agg(x ORDER BY sequencename)::text FROM (SELECT sequencename,last_value FROM pg_sequences WHERE schemaname='flowdesk' AND sequencename NOT IN ('task_status_activities_id_seq','workspace_invitations_id_seq')) x");}
}
