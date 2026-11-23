package com.flowdesk.persistence;

import com.flowdesk.identity.repository.UserRepository;
import com.flowdesk.support.IsolatedReadDatabase;
import com.flowdesk.workspace.domain.WorkspaceRole;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import com.flowdesk.workspace.repository.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={
        "spring.flyway.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true"
})
class JpaReadRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("jpa_tests").withUsername("postgres").withPassword(IsolatedReadDatabase.password());
    static IsolatedReadDatabase database;
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        database=new IsolatedReadDatabase(POSTGRES,true);
        registry.add("spring.datasource.url",()->database.url);
        registry.add("spring.datasource.username",()->"flowdesk_app");
        registry.add("spring.datasource.password",()->IsolatedReadDatabase.APP_PASSWORD);
    }
    @Autowired UserRepository users;
    @Autowired WorkspaceRepository workspaces;
    @Autowired WorkspaceMembershipRepository memberships;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactionManager;

    private long workspace(String name) throws Exception {
        return Long.parseLong(database.query("SELECT id FROM flowdesk.workspaces WHERE name='"+name+"'"));
    }

    @Test
    void readsFiveContractsWithAppRoleAndPreservesAllData() throws Exception {
        String before=database.snapshot();
        var alice=users.findByEmail("alice@example.test").orElseThrow();
        assertThat(alice.getId()).isPositive();
        assertThat(users.findById(alice.getId())).get().extracting("displayName").isEqualTo("Alice");
        Instant expected=Instant.parse(database.query("SELECT to_char(created_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM flowdesk.users WHERE email='alice@example.test'"));
        assertThat(alice.getCreatedAt()).isEqualTo(expected);
        assertThat(alice.getUpdatedAt()).isEqualTo(expected);
        long a=workspace("FlowDesk Demo A"),b=workspace("FlowDesk Demo B");
        assertThat(workspaces.findById(a)).get().extracting("name").isEqualTo("FlowDesk Demo A");
        assertThat(memberships.findByWorkspace_IdAndUser_Id(a,alice.getId())).get().extracting("role").isEqualTo(WorkspaceRole.OWNER);
        var eve=users.findByEmail("eve@example.test").orElseThrow();
        assertThat(memberships.findByWorkspace_IdAndUser_Id(a,eve.getId())).isEmpty();
        assertThat(memberships.findByWorkspace_IdAndUser_Id(b,eve.getId())).get().extracting("role").isEqualTo(WorkspaceRole.OWNER);
        var slice=memberships.findByUser_IdOrderByWorkspace_IdAsc(eve.getId(),PageRequest.of(0,2));
        assertThat(slice.getContent()).hasSize(1);
        assertThat(slice.getContent().getFirst().getWorkspace().getName()).isEqualTo("FlowDesk Demo B");
        assertThat(slice.hasNext()).isFalse();
        assertThat(users.findByEmail("missing@example.test")).isEmpty();
        assertThat(users.findById(Long.MAX_VALUE)).isEmpty();
        assertThat(workspaces.findById(Long.MAX_VALUE)).isEmpty();
        assertThat(memberships.findByWorkspace_IdAndUser_Id(Long.MAX_VALUE,alice.getId())).isEmpty();
        assertThat(database.snapshot()).isEqualTo(before);
        try(var c=database.app();var st=c.createStatement();var rs=st.executeQuery("SELECT current_user")) {
            rs.next();assertThat(rs.getString(1)).isEqualTo("flowdesk_app");
        }
    }

    @Test
    void toOneAssociationsStayLazyInTheUnadornedMembershipQuery() throws Exception {
        long alice=users.findByEmail("alice@example.test").orElseThrow().getId(), a=workspace("FlowDesk Demo A");
        TransactionTemplate tx=new TransactionTemplate(transactionManager);tx.setReadOnly(true);
        tx.executeWithoutResult(status->{
            var membership=memberships.findByWorkspace_IdAndUser_Id(a,alice).orElseThrow();
            assertThat(Hibernate.isInitialized(membership.getUser())).isFalse();
            assertThat(Hibernate.isInitialized(membership.getWorkspace())).isFalse();
            assertThat(membership.getUser().getDisplayName()).isEqualTo("Alice");
            assertThat(membership.getWorkspace().getName()).isEqualTo("FlowDesk Demo A");
        });
    }

    @Test
    void boundedSlicesFetchWorkspacesWithoutNPlusOne() throws Exception {
        long alice=users.findByEmail("alice@example.test").orElseThrow().getId();
        // An isolated fixture only: multiple workspaces exercise real pagination instead of the one-row local seed.
        database.sql("""
                WITH w AS (INSERT INTO flowdesk.workspaces(name) VALUES('Pagination C'),('Pagination D') RETURNING id)
                INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role)
                SELECT id,(SELECT id FROM flowdesk.users WHERE email='alice@example.test'),'OWNER' FROM w
                """);
        try {
            var statistics=emf.unwrap(SessionFactory.class).getStatistics();statistics.clear();
            var first=memberships.findByUser_IdOrderByWorkspace_IdAsc(alice,PageRequest.of(0,2));
            assertThat(first.getContent()).hasSize(2);assertThat(first.hasNext()).isTrue();
            first.forEach(m->assertThat(m.getWorkspace().getName()).isNotBlank());
            assertThat(first.map(m->m.getWorkspace().getId()).getContent()).isSorted();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
            var last=memberships.findByUser_IdOrderByWorkspace_IdAsc(alice,PageRequest.of(1,2));
            assertThat(last.getContent()).hasSize(1);assertThat(last.hasNext()).isFalse();
            assertThat(last.getContent().getFirst().getWorkspace().getId()).isGreaterThan(first.getContent().getLast().getWorkspace().getId());
        } finally {
            database.sql("DELETE FROM flowdesk.workspace_memberships WHERE workspace_id IN (SELECT id FROM flowdesk.workspaces WHERE name IN ('Pagination C','Pagination D')); DELETE FROM flowdesk.workspaces WHERE name IN ('Pagination C','Pagination D')");
        }
    }

    @Test
    void databaseRejectsWritesEvenOutsideReadOnlyTransactions() throws Exception {
        String before=database.snapshot();
        for(String sql:List.of(
                "INSERT INTO flowdesk.users(email,display_name) VALUES('forbidden@example.test','Forbidden')",
                "UPDATE flowdesk.users SET display_name='Forbidden' WHERE email='alice@example.test'",
                "DELETE FROM flowdesk.workspace_memberships", "TRUNCATE flowdesk.workspace_memberships",
                "CREATE TABLE flowdesk.forbidden_probe(id int)", "SELECT nextval('flowdesk.users_id_seq')",
                "SELECT * FROM flowdesk.flyway_schema_history")) {
            try(var c=database.app();var st=c.createStatement()) {
                assertThatThrownBy(()->st.execute(sql)).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
            }
        }
        EntityManager em=emf.createEntityManager();
        try {
            em.getTransaction().begin();
            assertThatThrownBy(()->em.createNativeQuery("UPDATE flowdesk.users SET display_name='Forbidden'").executeUpdate()).isInstanceOf(RuntimeException.class);
            em.getTransaction().rollback();
        } finally { em.close(); }
        assertThat(database.snapshot()).isEqualTo(before);
    }

    @Test
    void repositoriesExposeOnlyTheSevenApprovedReadQueries() {
        var declared=List.of(UserRepository.class,WorkspaceRepository.class,WorkspaceMembershipRepository.class).stream()
                .flatMap(c->Arrays.stream(c.getDeclaredMethods())).filter(m->Modifier.isPublic(m.getModifiers())).toList();
        assertThat(declared).extracting(java.lang.reflect.Method::getName).containsExactlyInAnyOrder(
                "findById", "findByEmail", "findById", "findByWorkspace_IdAndUser_Id",
                "existsByWorkspace_IdAndUser_Id", "findByUser_IdOrderByWorkspace_IdAsc", "findMembersByWorkspaceId");
        assertThat(Arrays.stream(UserRepository.class.getMethods()).map(java.lang.reflect.Method::getName)).doesNotContain("save","delete","findAll");
    }
}
