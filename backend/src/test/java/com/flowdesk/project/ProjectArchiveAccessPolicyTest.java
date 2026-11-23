package com.flowdesk.project;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProjectArchiveAccessPolicyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("project_archive_acl").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test void grantsOnlyArchiveColumnAndRejectsPartialOrBadCredentialState() throws Exception {
        var db = new IsolatedReadDatabase(POSTGRES, true);
        db.grantCreate().success(); db.grantRename().success(); db.grantProject().success(); db.grantProjectUpdate().success();
        var before = db.query("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND c.relname='projects' AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'UPDATE')");
        assertThat(before).isEqualTo("4");
        var applied = db.grantProjectArchive().success();
        assertThat(applied).contains("PROJECT_UPDATE");
        assertThat(db.query("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND c.relname='projects' AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'UPDATE')")).isEqualTo("5");
        var acl = db.snapshot();
        assertThat(db.grantProjectArchive().success()).contains("PROJECT_UPDATE");
        assertThat(db.snapshot()).isEqualTo(acl);
        assertThat(db.grantProjectArchive(java.util.Map.of("FLOWDESK_READ_APP_PASSWORD","0".repeat(64))).failure()).isNotBlank();
        assertThat(db.snapshot()).isEqualTo(acl);
    }
}
