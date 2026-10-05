// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.comment;

import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.comment.CommentDtos.AnchorRequest;
import eu.socle.comment.CommentDtos.CreateCommentRequest;
import eu.socle.comment.CommentDtos.UpdateCommentRequest;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.ReliabilityScoreService;
import eu.socle.notification.NotificationService;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID USER_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID USER_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID OWNER = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID MARTIN_A = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID MARTIN_B = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    static final UUID UNKNOWN = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    static final UUID SPACE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock AuditService auditService;
    @Mock NotificationService notificationService;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentStore documentStore;
    @Mock ReliabilityScoreService reliabilityScoreService;

    CommentService service;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
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
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY, name TEXT NOT NULL,
                  comment_policy TEXT NOT NULL DEFAULT 'members',
                  deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL, title TEXT NOT NULL,
                  body JSONB NOT NULL DEFAULT '{}',
                  current_version_no INTEGER NOT NULL DEFAULT 1,
                  deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_comments (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  parent_comment_id UUID REFERENCES document_comments(id) ON DELETE CASCADE,
                  author_id UUID NOT NULL REFERENCES users(id),
                  body TEXT NOT NULL,
                  anchor_block_id TEXT,
                  resolved BOOLEAN NOT NULL DEFAULT false,
                  resolved_by UUID REFERENCES users(id),
                  resolved_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  deleted_at TIMESTAMPTZ,
                  deleted_by UUID,
                  deleted_by_moderator BOOLEAN NOT NULL DEFAULT false,
                  author_display_name TEXT,
                  author_anonymized BOOLEAN NOT NULL DEFAULT false,
                  status TEXT NOT NULL DEFAULT 'ouvert',
                  anchor_exact TEXT,
                  anchor_prefix TEXT,
                  anchor_suffix TEXT,
                  anchor_version_no INTEGER
                )
                """);
        jdbc.update("INSERT INTO users VALUES (?,?,?,?), (?,?,?,?), (?,?,?,?), (?,?,?,?), (?,?,?,?)",
                USER_A, "a@ex.com", "Alice", "active",
                USER_B, "b@ex.com", "Bob", "active",
                OWNER, "o@ex.com", "Owner", "active",
                MARTIN_A, "ma@ex.com", "Martin", "active",
                MARTIN_B, "mb@ex.com", "Martin", "active");
        jdbc.update("INSERT INTO spaces (id, name, comment_policy) VALUES (?,?,?)",
                SPACE, "Eng", "members");
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, current_version_no)
                VALUES (?, ?, 'Doc', '{}'::jsonb, 3)
                """, DOC, SPACE);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document_comments");
        jdbc.update("UPDATE spaces SET comment_policy = 'members' WHERE id = ?", SPACE);
        jdbc.update("UPDATE documents SET deleted_at = NULL, current_version_no = 3 WHERE id = ?", DOC);
        service = new CommentService(
                jdbc, userSyncService, authorizationService, auditService, notificationService,
                documentRepository, documentStore, reliabilityScoreService);
        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            Jwt jwt = inv.getArgument(0);
            return user(UUID.fromString(jwt.getSubject()));
        });
        DocumentEntity doc = new DocumentEntity();
        doc.setId(DOC);
        doc.setSpaceId(SPACE);
        doc.setTitle("Doc");
        doc.setBody(Map.of("type", "doc", "content", List.of()));
        doc.setCurrentVersionNo(3);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(doc));
        when(documentStore.readCurrentContent(eq(DOC), any())).thenReturn(Map.of(
                "type", "doc",
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", "Hello Passage ancré ici world"))))));
        doNothing().when(reliabilityScoreService).onDocumentCommentChanged(any());
        doNothing().when(auditService).record(any(), any(Boolean.class), any(), any(), any(), any(), any());
    }

    @Test
    void create_anchored_holdsWhenOtherParagraphChanges_thenDetachesOnRewrite() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);

        String v3 = "Intro\nPassage ancré ici\nFin\n";
        when(documentStore.readCurrentContent(eq(DOC), any())).thenReturn(textDoc(v3));
        TextQuoteAnchor built = CommentAnchorResolver.buildFromOffset(
                v3, v3.indexOf("Passage ancré ici"), "Passage ancré ici", null, 3);

        var created = service.create(jwt(USER_A), DOC, new CreateCommentRequest(
                "Note",
                new AnchorRequest(built.exact(), built.prefix(), built.suffix(), null),
                null));
        assertThat(created.anchor()).isNotNull();
        assertThat(created.anchor().attached()).isTrue();

        // v4 — autre paragraphe
        String v4 = "Intro changée\nPassage ancré ici\nFin\n";
        when(documentStore.readCurrentContent(eq(DOC), any())).thenReturn(textDoc(v4));
        var page4 = service.list(jwt(USER_A), DOC, null, null);
        assertThat(page4.threads()).hasSize(1);
        assertThat(page4.threads().getFirst().anchor().attached()).isTrue();
        assertThat(page4.detached()).isEmpty();

        // v5 — passage réécrit
        String v5 = "Intro changée\nPassage complètement réécrit\nFin\n";
        when(documentStore.readCurrentContent(eq(DOC), any())).thenReturn(textDoc(v5));
        var page5 = service.list(jwt(USER_A), DOC, null, null);
        assertThat(page5.detached()).hasSize(1);
        assertThat(page5.detached().getFirst().anchor().attached()).isFalse();
    }

    @Test
    void publicReader_cannotCommentWithMembers_canWithAllReaders() {
        when(authorizationService.hasRelation(USER_B, "document", DOC, "editor")).thenReturn(false);
        when(authorizationService.hasRelation(USER_B, "space", SPACE, "viewer")).thenReturn(false);
        when(authorizationService.hasRelation(USER_B, "document", DOC, "viewer")).thenReturn(true);
        doNothing().when(authorizationService).requireDocumentRelation(USER_B, DOC, "viewer");

        assertThatThrownBy(() -> service.create(jwt(USER_B), DOC, new CreateCommentRequest("x", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        jdbc.update("UPDATE spaces SET comment_policy = 'all_readers' WHERE id = ?", SPACE);
        var created = service.create(jwt(USER_B), DOC, new CreateCommentRequest("ok", null, null));
        assertThat(created.body()).isEqualTo("ok");
    }

    @Test
    void nonAuthor_cannotEdit_ownerCanModerateDelete() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        var created = service.create(jwt(USER_A), DOC, new CreateCommentRequest("mine", null, null));

        when(authorizationService.hasRelation(USER_B, "document", DOC, "viewer")).thenReturn(true);
        doNothing().when(authorizationService).requireDocumentRelation(USER_B, DOC, "viewer");
        assertThatThrownBy(() -> service.update(jwt(USER_B), created.id(), new UpdateCommentRequest("hack")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        when(authorizationService.hasRelation(OWNER, "space", SPACE, "owner")).thenReturn(true);
        var deleted = service.delete(jwt(OWNER), created.id());
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.deletedLabel()).contains("modérateur");
    }

    @Test
    void mention_withoutAccess_genericWarning_noNotification() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(USER_B, "document", DOC, "viewer")).thenReturn(false);

        var created = service.create(jwt(USER_A), DOC, new CreateCommentRequest(
                "Salut @[Bob](" + USER_B + ")", null, null));

        assertThat(created.mentionWarnings()).hasSize(1);
        assertThat(created.mentionWarnings().getFirst().message())
                .isEqualTo(CommentService.MENTION_NO_NOTIFY);
        verify(notificationService, never()).create(eq(USER_B), any(), anyMap());
    }

    @Test
    void mention_unknownUuid_sameGenericWarningAsNoAccess() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(UNKNOWN, "document", DOC, "viewer")).thenReturn(false);

        var created = service.create(jwt(USER_A), DOC, new CreateCommentRequest(
                "Hey @[Ghost](" + UNKNOWN + ")", null, null));

        assertThat(created.mentionWarnings()).hasSize(1);
        assertThat(created.mentionWarnings().getFirst().message())
                .isEqualTo(CommentService.MENTION_NO_NOTIFY);
        verify(notificationService, never()).create(any(), any(), anyMap());
    }

    @Test
    void mention_freeTextMartin_notifiesNobody_evenIfTwoMartinsExist() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(MARTIN_A, "document", DOC, "viewer")).thenReturn(true);
        when(authorizationService.hasRelation(MARTIN_B, "document", DOC, "viewer")).thenReturn(true);

        var created = service.create(jwt(USER_A), DOC, new CreateCommentRequest(
                "Ping @Martin please", null, null));

        assertThat(created.mentionWarnings()).isEmpty();
        verify(notificationService, never()).create(any(), any(), anyMap());
    }

    @Test
    void mention_structuredMartinB_notifiesOnlyB() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(MARTIN_A, "document", DOC, "viewer")).thenReturn(true);
        when(authorizationService.hasRelation(MARTIN_B, "document", DOC, "viewer")).thenReturn(true);

        service.create(jwt(USER_A), DOC, new CreateCommentRequest(
                "Ping @[Martin](" + MARTIN_B + ")", null, null));

        verify(notificationService).create(eq(MARTIN_B), eq(CommentService.NOTIF_TYPE_MENTION), anyMap());
        verify(notificationService, never()).create(eq(MARTIN_A), any(), anyMap());
    }

    @Test
    void mention_withAccess_createsNotificationWithoutTitle() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(USER_B, "document", DOC, "viewer")).thenReturn(true);

        service.create(jwt(USER_A), DOC, new CreateCommentRequest(
                "Hey @[Bob](" + USER_B + ")", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).create(eq(USER_B), eq(CommentService.NOTIF_TYPE_MENTION), payload.capture());
        assertThat(payload.getValue()).containsKeys("document_id", "comment_id", "mentioned_by");
        assertThat(payload.getValue()).doesNotContainKeys("title", "document_title", "excerpt", "body");
    }

    @Test
    void suggestMentions_requiresCommentPermission_andFiltersReaders() {
        when(authorizationService.hasRelation(USER_B, "document", DOC, "editor")).thenReturn(false);
        when(authorizationService.hasRelation(USER_B, "space", SPACE, "viewer")).thenReturn(false);

        assertThatThrownBy(() -> service.suggestMentions(jwt(USER_B), DOC, "Ma"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(MARTIN_A, "document", DOC, "viewer")).thenReturn(true);
        when(authorizationService.hasRelation(MARTIN_B, "document", DOC, "viewer")).thenReturn(false);

        assertThat(service.suggestMentions(jwt(USER_A), DOC, "M")).isEmpty(); // préfixe < 2
        var suggestions = service.suggestMentions(jwt(USER_A), DOC, "Ma");
        assertThat(suggestions).extracting(s -> s.userId()).containsExactly(MARTIN_A);
        assertThat(suggestions).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void trash_hidesComments_restoreShowsThem() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        doNothing().when(authorizationService).requireDocumentRelation(USER_A, DOC, "viewer");
        service.create(jwt(USER_A), DOC, new CreateCommentRequest("keep", null, null));

        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(jwt(USER_A), DOC, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        DocumentEntity doc = new DocumentEntity();
        doc.setId(DOC);
        doc.setSpaceId(SPACE);
        doc.setBody(Map.of());
        doc.setCurrentVersionNo(3);
        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(doc));
        assertThat(service.list(jwt(USER_A), DOC, null, null).threads()).hasSize(1);
    }

    @Test
    void anonymizeAuthor_andExportPersonalComments() {
        when(authorizationService.hasRelation(USER_A, "document", DOC, "editor")).thenReturn(true);
        service.create(jwt(USER_A), DOC, new CreateCommentRequest("perso", null, null));

        int n = service.anonymizeAuthor(USER_A);
        assertThat(n).isEqualTo(1);
        doNothing().when(authorizationService).requireDocumentRelation(USER_B, DOC, "viewer");
        when(authorizationService.hasRelation(USER_B, "document", DOC, "viewer")).thenReturn(true);
        var page = service.list(jwt(USER_B), DOC, null, null);
        assertThat(page.threads().getFirst().authorDisplayName()).isEqualTo(CommentService.ANONYMIZED_LABEL);
        assertThat(page.threads().getFirst().authorAnonymized()).isTrue();

        var exported = service.exportPersonalComments(USER_A);
        assertThat(exported).hasSize(1);
        assertThat(exported.getFirst().body()).isEqualTo("perso");
    }

    private static Map<String, Object> textDoc(String plain) {
        String[] lines = plain.split("\n", -1);
        java.util.List<Map<String, Object>> paras = new java.util.ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) continue;
            paras.add(Map.of(
                    "type", "paragraph",
                    "content", List.of(Map.of("type", "text", "text", line))));
        }
        return Map.of("type", "doc", "content", paras);
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@ex.com");
        u.setDisplayName("U");
        return u;
    }

    private static Jwt jwt(UUID sub) {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        return Jwt.withTokenValue("t").header("alg", "none").subject(sub.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
    }
}
