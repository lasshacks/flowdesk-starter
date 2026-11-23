package com.flowdesk.persistence;

import com.flowdesk.identity.domain.User;
import com.flowdesk.project.domain.Project;
import com.flowdesk.support.IsolatedReadDatabase;
import com.flowdesk.workspace.domain.Workspace;
import com.flowdesk.workspace.domain.WorkspaceMembership;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JpaSchemaValidationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("jpa_validation_tests").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @ParameterizedTest
    @ValueSource(strings={"schema","column","version","projects","projectVersion","tasks","taskVersion"})
    void validateFailsWithoutRepairingTheDatabase(String target) throws Exception {
        var db=new IsolatedReadDatabase(POSTGRES,true);
        db.sql(switch (target) {
            case "schema" -> "DROP SCHEMA flowdesk CASCADE";
            case "version" -> "ALTER TABLE flowdesk.workspaces DROP COLUMN version";
            case "projectVersion" -> "ALTER TABLE flowdesk.projects DROP COLUMN version";
            case "projects" -> "DROP TABLE flowdesk.projects CASCADE";
            case "taskVersion" -> "ALTER TABLE flowdesk.tasks DROP COLUMN version";
            // The V7 activity FK must be removed too to create the intended missing-table fixture.
            case "tasks" -> "DROP TABLE flowdesk.tasks CASCADE";
            default -> "ALTER TABLE flowdesk.users DROP COLUMN display_name";
        });
        var registry=new StandardServiceRegistryBuilder().applySettings(Map.of(
                "hibernate.connection.url",db.url,"hibernate.connection.username","flowdesk_app",
                "hibernate.connection.password",IsolatedReadDatabase.APP_PASSWORD,
                "hibernate.hbm2ddl.auto","validate")).build();
        try {
            assertThatThrownBy(()->new MetadataSources(registry).addAnnotatedClass(User.class)
                    .addAnnotatedClass(Workspace.class).addAnnotatedClass(WorkspaceMembership.class).addAnnotatedClass(Project.class).addAnnotatedClass(com.flowdesk.task.domain.Task.class)
                    .buildMetadata().buildSessionFactory()).isInstanceOf(RuntimeException.class).hasMessageContaining("Schema-validation");
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
        if(target.equals("schema")) assertThat(db.query("SELECT count(*) FROM pg_namespace WHERE nspname='flowdesk'")).isEqualTo("0");
        else if(target.equals("taskVersion")) assertThat(db.query("SELECT count(*) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attname='version' AND NOT attisdropped")).isEqualTo("0");
        else if(target.equals("tasks")) assertThat(db.query("SELECT to_regclass('flowdesk.tasks') IS NULL")).isEqualTo("t");
        else if(target.equals("projects")) assertThat(db.query("SELECT to_regclass('flowdesk.projects') IS NULL")).isEqualTo("t");
        else if(target.equals("projectVersion")) assertThat(db.query("SELECT count(*) FROM pg_attribute WHERE attrelid='flowdesk.projects'::regclass AND attname='version' AND NOT attisdropped")).isEqualTo("0");
        else if(target.equals("version")) assertThat(db.query("SELECT count(*) FROM pg_attribute WHERE attrelid='flowdesk.workspaces'::regclass AND attname='version' AND NOT attisdropped")).isEqualTo("0");
        else assertThat(db.query("SELECT count(*) FROM pg_attribute WHERE attrelid='flowdesk.users'::regclass AND attname='display_name' AND NOT attisdropped")).isEqualTo("0");
    }
}
