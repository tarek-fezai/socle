// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class DirectoryServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final UUID CALLER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID ACTIVE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DISABLED = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    DirectoryService service;
    DirectoryProperties props;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY,
                  email TEXT NOT NULL,
                  display_name TEXT NOT NULL,
                  status TEXT NOT NULL,
                  avatar_initials TEXT,
                  last_login_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE user_platform_roles (
                  user_id UUID NOT NULL, role TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE groups (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE group_members (
                  group_id UUID NOT NULL, user_id UUID NOT NULL, role TEXT NOT NULL DEFAULT 'contributeur'
                )
                """);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM group_members");
        jdbc.update("DELETE FROM groups");
        jdbc.update("DELETE FROM user_platform_roles");
        jdbc.update("DELETE FROM users");
        jdbc.update(
                "INSERT INTO users (id, email, display_name, status, avatar_initials) VALUES (?,?,?,?,?), (?,?,?,?,?), (?,?,?,?,?)",
                CALLER, "caller@ex.com", "Caller", "active", "CA",
                ACTIVE, "francois@ex.com", "François Dupont", "active", "FD",
                DISABLED, "off@ex.com", "François Off", "disabled", "FO");
        props = new DirectoryProperties();
        props.setRateLimitPerMinute(30);
        service = new DirectoryService(jdbc, props, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void qMissingOrShort_400() {
        assertThatThrownBy(() -> service.searchUsers(CALLER, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.searchUsers(CALLER, "é", null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void accentInsensitive_andDisabledExcluded_andExactlyFourFields() throws Exception {
        var res = service.searchUsers(CALLER, "francois", 20);
        assertThat(res.users()).hasSize(1);
        assertThat(res.users().get(0).displayName()).isEqualTo("François Dupont");
        assertThat(res.users().get(0).id()).isEqualTo(ACTIVE);
        JsonNode node = MAPPER.valueToTree(res.users().get(0));
        assertThat(node.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "displayName", "email", "initials");
        assertThat(res.users().stream().map(u -> u.id())).doesNotContain(DISABLED);
    }

    @Test
    void rateLimit_429() {
        props.setRateLimitPerMinute(2);
        service.searchUsers(CALLER, "fr", 8);
        service.searchUsers(CALLER, "fr", 8);
        assertThatThrownBy(() -> service.searchUsers(CALLER, "fr", 8))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}
