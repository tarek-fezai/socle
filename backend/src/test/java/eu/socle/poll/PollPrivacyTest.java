// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.poll;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.poll.PollDtos.PollView;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Confidentialité des votes : agrégats visibles, jamais la liste nominative ;
 * export RGPD = uniquement ses propres votes.
 */
@ExtendWith(MockitoExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class PollPrivacyTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock DocumentRepository documentRepository;
    @Mock AuditService auditService;

    JdbcTemplate jdbc;
    PollService service;
    Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

    static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID POLL = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL,
                  status TEXT NOT NULL DEFAULT 'active')
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                  id UUID PRIMARY KEY, title TEXT NOT NULL, deleted_at TIMESTAMPTZ)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS polls (
                  id UUID PRIMARY KEY,
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  question TEXT NOT NULL,
                  options JSONB NOT NULL,
                  closed_at TIMESTAMPTZ,
                  archived_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now())
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS poll_votes (
                  poll_id UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
                  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                  option TEXT NOT NULL,
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  PRIMARY KEY (poll_id, user_id))
                """);
        jdbc.update("DELETE FROM poll_votes");
        jdbc.update("DELETE FROM polls");
        jdbc.update("DELETE FROM documents");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'a@x', 'Alice'), (?, 'b@x', 'Bob')",
                ALICE, BOB);
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, 'Doc')", DOC);
        jdbc.update("""
                INSERT INTO polls (id, document_id, question, options)
                VALUES (?, ?, 'Couleur ?', '["Rouge","Bleu"]'::jsonb)
                """, POLL, DOC);

        service = new PollService(
                jdbc, new ObjectMapper(), userSyncService, authorizationService,
                documentRepository, auditService, clock);

        DocumentEntity doc = new DocumentEntity();
        doc.setId(DOC);
        lenient().when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(doc));
        lenient().when(authorizationService.hasRelation(any(), eq("document"), eq(DOC), eq("viewer")))
                .thenReturn(true);
        lenient().when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            UUID id = UUID.fromString(jwt.getSubject());
            UserEntity u = new UserEntity();
            u.setId(id);
            u.setEmail(id + "@x");
            u.setDisplayName("U");
            return u;
        });
    }

    @Test
    void get_exposesAggregatesAndOwnVote_neverOtherVoters() {
        voteAs(ALICE, "Rouge");
        voteAs(BOB, "Bleu");

        PollView aliceView = service.get(jwt(ALICE), POLL);
        assertThat(aliceView.myVote()).isEqualTo("Rouge");
        assertThat(aliceView.results()).extracting(r -> r.option() + ":" + r.count())
                .containsExactly("Rouge:1", "Bleu:1");
        assertThat(aliceView.totalVotes()).isEqualTo(2);
        // Aucun champ nominatif dans la vue
        assertThat(aliceView.toString()).doesNotContain(BOB.toString());
        assertThat(aliceView.toString()).doesNotContainIgnoringCase("voters");

        PollView bobView = service.get(jwt(BOB), POLL);
        assertThat(bobView.myVote()).isEqualTo("Bleu");
        assertThat(bobView.results()).extracting(r -> r.option() + ":" + r.count())
                .containsExactly("Rouge:1", "Bleu:1");
    }

    @Test
    void exportPersonalVotes_onlyOwnRows() {
        voteAs(ALICE, "Rouge");
        voteAs(BOB, "Bleu");

        assertThat(service.exportPersonalVotes(ALICE))
                .hasSize(1)
                .first()
                .satisfies(v -> {
                    assertThat(v.pollId()).isEqualTo(POLL);
                    assertThat(v.option()).isEqualTo("Rouge");
                    assertThat(v.documentId()).isEqualTo(DOC);
                });
        assertThat(service.exportPersonalVotes(BOB)).extracting(v -> v.option())
                .containsExactly("Bleu");
    }

    @Test
    void syncFromBody_archivesMissingPoll_keepsVotes() {
        voteAs(ALICE, "Rouge");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "doc");
        body.put("content", List.of());
        service.syncFromBody(DOC, body);

        Instant archived = jdbc.queryForObject(
                "SELECT archived_at FROM polls WHERE id = ?", Instant.class, POLL);
        assertThat(archived).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM poll_votes WHERE poll_id = ?", Integer.class, POLL))
                .isEqualTo(1);
    }

    private void voteAs(UUID userId, String option) {
        jdbc.update("UPDATE polls SET archived_at = NULL, closed_at = NULL WHERE id = ?", POLL);
        service.vote(jwt(userId), POLL, option);
    }

    private static Jwt jwt(UUID userId) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
