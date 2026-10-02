// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class ActivityEventServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    ActivityEventService service;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() throws Exception {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id), title TEXT NOT NULL
                )
                """);
        String sql = new ClassPathResource("db/migration/V31__activity_events.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);

        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, 'a@x', 'Ada', 'active')", USER);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Doc')", DOC, SPACE);
    }

    @BeforeEach
    void setUp() {
        service = new ActivityEventService(jdbc, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        jdbc.update("DELETE FROM activity_events");
    }

    @Test
    void record_insertsEvent() {
        service.record(ActivityEventTypes.COMMENT, USER, DOC, SPACE, Map.of("k", "v"));

        Integer n = jdbc.queryForObject("SELECT count(*) FROM activity_events", Integer.class);
        assertThat(n).isEqualTo(1);
        String type = jdbc.queryForObject("SELECT event_type FROM activity_events", String.class);
        assertThat(type).isEqualTo("comment");
    }

    @Test
    void purge_removesOlderThan90Days() {
        Instant old = NOW.minusSeconds(91L * 24 * 3600);
        jdbc.update("""
                INSERT INTO activity_events
                  (id, event_type, actor_user_id, document_id, space_id, payload, created_at)
                VALUES (?, 'comment', ?, ?, ?, '{}'::jsonb, ?)
                """,
                UUID.randomUUID(), USER, DOC, SPACE, Timestamp.from(old));
        jdbc.update("""
                INSERT INTO activity_events
                  (id, event_type, actor_user_id, document_id, space_id, payload, created_at)
                VALUES (?, 'comment', ?, ?, ?, '{}'::jsonb, ?)
                """,
                UUID.randomUUID(), USER, DOC, SPACE, Timestamp.from(NOW));

        int purged = service.purgeOlderThanRetention();

        assertThat(purged).isEqualTo(1);
        Integer remaining = jdbc.queryForObject("SELECT count(*) FROM activity_events", Integer.class);
        assertThat(remaining).isEqualTo(1);
    }
}
