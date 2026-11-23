package com.flowdesk.common.health;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
})
class DatabaseHealthTest {

    private static final String APP_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("flowdesk")
            .withUsername("postgres")
            .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) throws Exception {
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var setting = admin.prepareStatement("SELECT set_config('flowdesk.app_password', ?, false)")) {
                setting.setString(1, APP_PASSWORD);
                setting.execute();
            }
            String policy = new ClassPathResource("db/app-role.sql").getContentAsString(StandardCharsets.UTF_8);
            try (var statement = admin.createStatement()) {
                statement.execute(policy);
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "flowdesk_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    HealthContributorRegistry contributors;

    @Test
    void appAccountCanConnectWithoutDdlOrAdministratorPrivileges() throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT current_user, 1")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("flowdesk_app");
                assertThat(result.getInt(2)).isEqualTo(1);
            }
            try (var result = statement.executeQuery("""
                    SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls,
                           has_database_privilege(current_database(), 'CREATE'),
                           has_database_privilege(current_database(), 'TEMP'),
                           has_schema_privilege('public', 'CREATE')
                    FROM pg_roles WHERE rolname = current_user
                    """)) {
                assertThat(result.next()).isTrue();
                for (int column = 1; column <= 8; column++) {
                    assertThat(result.getBoolean(column)).isFalse();
                }
            }
            assertThatThrownBy(() -> statement.execute("CREATE TABLE public.forbidden_probe (id integer)"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> statement.execute("CREATE TEMP TABLE forbidden_temp_probe (id integer)"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void actuatorIncludesRealDatabaseButDoesNotExposeDetails() {
        assertThat(contributors.getContributor("db")).isInstanceOf(DataSourceHealthIndicator.class);
        assertHealth(HttpStatus.OK, "UP");
    }

    @Test
    void databaseOutageChangesActuatorToDownAndRecovers() {
        assertHealth(HttpStatus.OK, "UP");
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            assertHealth(HttpStatus.SERVICE_UNAVAILABLE, "DOWN");
            ResponseEntity<JsonNode> appHealth = restTemplate.getForEntity("/api/health", JsonNode.class);
            assertThat(appHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(appHealth.getBody().path("status").asText()).isEqualTo("UP");
        } finally {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertHealth(HttpStatus.OK, "UP"));
    }

    private void assertHealth(HttpStatus status, String health) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/actuator/health", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.size()).isEqualTo(1);
        assertThat(body.path("status").asText()).isEqualTo(health);
    }
}
