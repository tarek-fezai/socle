// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentViewServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock DocumentRepository documentRepository;

    DocumentViewService service;
    Jwt jwt;

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
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        String sql = new ClassPathResource("db/migration/V31__document_view_counts.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);

        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Doc')", DOC, SPACE);
    }

    @BeforeEach
    void setUp() {
        service = new DocumentViewService(
                jdbc, userSyncService, authorizationService, documentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        UserEntity user = new UserEntity();
        user.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();

        DocumentEntity entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setSpaceId(SPACE);
        entity.setTitle("Doc");
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        jdbc.update("DELETE FROM document_view_counts");
    }

    @Test
    void accessible_incrementsCount() {
        when(authorizationService.hasRelation(USER, "document", DOC, "viewer")).thenReturn(true);

        service.recordView(jwt, DOC);
        service.recordView(jwt, DOC);

        LocalDate day = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
        Long count = jdbc.queryForObject(
                "SELECT count FROM document_view_counts WHERE document_id = ? AND day = ?",
                Long.class, DOC, java.sql.Date.valueOf(day));
        assertThat(count).isEqualTo(2L);
    }

    @Test
    void inaccessible_doesNotIncrement() {
        when(authorizationService.hasRelation(USER, "document", DOC, "viewer")).thenReturn(false);

        assertThatThrownBy(() -> service.recordView(jwt, DOC))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Integer n = jdbc.queryForObject("SELECT count(*) FROM document_view_counts", Integer.class);
        assertThat(n).isZero();
    }

    @Test
    void schema_hasNoUserIdColumn() throws Exception {
        try (Connection c = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            Set<String> columns = new HashSet<>();
            try (ResultSet rs = meta.getColumns(null, null, "document_view_counts", null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            assertThat(columns).contains("document_id", "day", "count");
            assertThat(columns).doesNotContain("user_id");
        }
    }
}
