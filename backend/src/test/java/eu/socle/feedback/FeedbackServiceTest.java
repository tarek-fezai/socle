// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.feedback;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.feedback.FeedbackDtos.FeedbackView;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    static final UUID EDITOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID READER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID READER2 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID OUTSIDER = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock DocumentRepository documentRepository;

    FeedbackService service;
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
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute(new ClassPathResource("db/migration/V34__document_feedback.sql")
                .getContentAsString(StandardCharsets.UTF_8));

        for (UUID id : new UUID[] {EDITOR, READER, READER2, OUTSIDER}) {
            jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, ?, 'u', 'active')",
                    id, id + "@x");
        }
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
        jdbc.update("INSERT INTO documents (id, space_id, title) VALUES (?, ?, 'Doc')", DOC, SPACE);
    }

    @BeforeEach
    void setUp() {
        service = new FeedbackService(
                jdbc, userSyncService, authorizationService, documentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();

        DocumentEntity entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setSpaceId(SPACE);
        entity.setTitle("Doc");
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(entity));

        when(authorizationService.hasRelation(any(), org.mockito.ArgumentMatchers.eq("document"),
                org.mockito.ArgumentMatchers.eq(DOC), org.mockito.ArgumentMatchers.eq("viewer")))
                .thenAnswer(inv -> !OUTSIDER.equals(inv.getArgument(0)));
        when(authorizationService.hasRelation(EDITOR, "document", DOC, "editor")).thenReturn(true);

        jdbc.update("DELETE FROM document_feedback");
        actAs(READER);
    }

    void actAs(UUID userId) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        when(userSyncService.syncFromJwt(any())).thenReturn(user);
    }

    @Test
    void get_withoutVote_returnsNullVote_andNoTotalsForReader() {
        FeedbackView v = service.get(jwt, DOC);

        assertThat(v.myVote()).isNull();
        assertThat(v.totals()).isNull();
    }

    @Test
    void put_isUpsert_lastVoteWins() {
        assertThat(service.put(jwt, DOC, true).myVote()).isTrue();
        assertThat(service.put(jwt, DOC, false).myVote()).isFalse();

        assertThat(service.get(jwt, DOC).myVote()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_feedback", Integer.class)).isEqualTo(1);
    }

    @Test
    void totals_areVisibleToEditorsOnly() {
        service.put(jwt, DOC, true);
        actAs(READER2);
        service.put(jwt, DOC, false);
        actAs(EDITOR);
        service.put(jwt, DOC, true);

        FeedbackView editorView = service.get(jwt, DOC);
        assertThat(editorView.myVote()).isTrue();
        assertThat(editorView.totals()).isNotNull();
        assertThat(editorView.totals().yes()).isEqualTo(2);
        assertThat(editorView.totals().no()).isEqualTo(1);

        actAs(READER2);
        FeedbackView readerView = service.get(jwt, DOC);
        assertThat(readerView.myVote()).isFalse();
        assertThat(readerView.totals()).isNull();
        assertThat(service.put(jwt, DOC, false).totals()).isNull();
    }

    @Test
    void nonViewer_getsNotFound_andNothingIsStored() {
        actAs(OUTSIDER);

        assertThatThrownBy(() -> service.put(jwt, DOC, true))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> service.get(jwt, DOC)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_feedback", Integer.class)).isZero();
    }

    @Test
    void unknownDocument_getsNotFound() {
        UUID other = UUID.randomUUID();
        when(documentRepository.findActiveById(other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(jwt, other))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
