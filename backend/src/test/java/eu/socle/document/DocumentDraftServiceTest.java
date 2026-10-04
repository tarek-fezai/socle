// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDraftService.DraftView;
import eu.socle.user.UserEntity;
import eu.socle.user.UserRepository;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Brouillons sur PostgreSQL réel : la migration V35 réelle est appliquée ; authz / identité mockés,
 * verrou d'édition réel ({@code document_edit_locks}).
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentDraftServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID BOB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID TRASHED = UUID.fromString("11111111-1111-1111-1111-111111111112");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock UserRepository userRepository;

    DocumentDraftService service;
    UUID currentUser;
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
        jdbc.execute("CREATE TABLE users (id UUID PRIMARY KEY, email TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, title TEXT NOT NULL,
                  current_version_no INT NOT NULL DEFAULT 1, deleted_at TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_versions (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  version_no INT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE audit_log_events (
                  id BIGSERIAL PRIMARY KEY, action TEXT NOT NULL, resource_id UUID
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_edit_locks (
                  document_id UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
                  holder_user_id UUID NOT NULL REFERENCES users(id),
                  acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        // Migration réelle V35 (et non une copie) : le test échoue si elle dérive.
        String v35 = new String(
                new ClassPathResource("db/migration/V35__document_drafts.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.execute(v35);

        jdbc.update("INSERT INTO users (id, email) VALUES (?, 'alice@x.eu'), (?, 'bob@x.eu')", ALICE, BOB);
        jdbc.update("INSERT INTO documents (id, title, current_version_no) VALUES (?, 'Doc', 3)", DOC);
        jdbc.update("INSERT INTO documents (id, title, deleted_at) VALUES (?, 'Corbeille', now())", TRASHED);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document_drafts");
        jdbc.update("DELETE FROM document_edit_locks");
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM audit_log_events");

        EditLockProperties props = new EditLockProperties();
        props.setHeartbeatSeconds(15);
        props.setTtlSeconds(45);
        props.validate();
        EditLockService locks = new EditLockService(
                jdbc, authorizationService, userSyncService, userRepository, props, java.time.Clock.systemUTC());
        service = new DocumentDraftService(
                jdbc, new ObjectMapper(), userSyncService, authorizationService, locks);

        currentUser = ALICE;
        when(userSyncService.syncFromJwt(any())).thenAnswer(inv -> {
            UserEntity u = new UserEntity();
            u.setId(currentUser);
            return u;
        });
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub").build();
    }

    private static void lockFor(UUID user) {
        jdbc.update("DELETE FROM document_edit_locks WHERE document_id = ?", DOC);
        jdbc.update("INSERT INTO document_edit_locks (document_id, holder_user_id) VALUES (?, ?)", DOC, user);
    }

    private static int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static void assertStatus(Throwable t, HttpStatus expected) {
        assertThat(t).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) t).getStatusCode()).isEqualTo(expected);
    }

    // ---------- critique : un brouillon n'est jamais une version ----------

    @Test
    void put20Drafts_createsNoVersion_noAudit_andKeepsDocumentUntouched() {
        lockFor(ALICE);

        for (int i = 1; i <= 20; i++) {
            service.put(jwt, DOC, "Titre " + i, VersionTestSupport.doc("v" + i), 3);
        }

        assertThat(count("SELECT count(*) FROM document_versions WHERE document_id = ?", DOC)).isZero();
        assertThat(count("SELECT current_version_no FROM documents WHERE id = ?", DOC)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM audit_log_events")).isZero();
        // upsert : une seule ligne, dernière valeur
        assertThat(count("SELECT count(*) FROM document_drafts WHERE document_id = ?", DOC)).isEqualTo(1);
        DraftView draft = service.get(jwt, DOC);
        assertThat(draft.title()).isEqualTo("Titre 20");
        assertThat(draft.body()).isEqualTo(VersionTestSupport.doc("v20"));
        assertThat(draft.baseVersionNo()).isEqualTo(3);
        assertThat(draft.updatedAt()).isNotNull();
    }

    /**
     * Garantie structurelle « zéro commit Git / audit / outbox / activité » : le service de
     * brouillons ne peut pas atteindre ces collaborateurs (aucune dépendance injectée).
     */
    @Test
    void draftService_hasNoAuditGitOrEventDependencies() {
        List<Class<?>> dependencies = Arrays.stream(DocumentDraftService.class.getDeclaredConstructors())
                .flatMap(c -> Arrays.stream(c.getParameterTypes()))
                .collect(java.util.stream.Collectors.toList());
        List<Class<?>> fields = Arrays.stream(DocumentDraftService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .collect(java.util.stream.Collectors.toList());

        for (Class<?> forbidden : List.of(
                eu.socle.audit.AuditService.class,
                eu.socle.storage.DocumentStore.class,
                eu.socle.activity.ActivityEventService.class,
                org.springframework.context.ApplicationEventPublisher.class,
                DocumentService.class)) {
            assertThat(dependencies).doesNotContain(forbidden);
            assertThat(fields).doesNotContain(forbidden);
        }
    }

    // ---------- privacy : visible uniquement par son auteur ----------

    @Test
    void getDraftOfAnotherUser_is404() {
        lockFor(ALICE);
        service.put(jwt, DOC, "Privé", VersionTestSupport.doc("a"), 3);

        currentUser = BOB;
        assertThatThrownBy(() -> service.get(jwt, DOC))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.delete(jwt, DOC))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));

        // le brouillon d'Alice n'a pas été touché
        assertThat(count("SELECT count(*) FROM document_drafts WHERE user_id = ?", ALICE)).isEqualTo(1);
    }

    @Test
    void drafts_areIndependentPerUser() {
        lockFor(ALICE);
        service.put(jwt, DOC, "Alice", VersionTestSupport.doc("a"), 3);
        currentUser = BOB;
        lockFor(BOB);
        service.put(jwt, DOC, "Bob", VersionTestSupport.doc("b"), 3);

        assertThat(service.get(jwt, DOC).title()).isEqualTo("Bob");
        currentUser = ALICE;
        assertThat(service.get(jwt, DOC).title()).isEqualTo("Alice");
        assertThat(count("SELECT count(*) FROM document_drafts")).isEqualTo(2);
    }

    // ---------- verrou d'édition / droits ----------

    @Test
    void putWithoutEditLock_is409_andWritesNothing() {
        // aucun verrou, puis verrou détenu par quelqu'un d'autre
        assertThatThrownBy(() -> service.put(jwt, DOC, "T", VersionTestSupport.doc("a"), 3))
                .satisfies(t -> {
                    assertStatus(t, HttpStatus.CONFLICT);
                    assertThat(((ResponseStatusException) t).getReason()).contains("Verrou d'édition");
                });
        lockFor(BOB);
        assertThatThrownBy(() -> service.put(jwt, DOC, "T", VersionTestSupport.doc("a"), 3))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));

        assertThat(count("SELECT count(*) FROM document_drafts")).isZero();
    }

    @Test
    void putWithExpiredLock_is409() {
        lockFor(ALICE);
        jdbc.update("UPDATE document_edit_locks SET heartbeat_at = now() - interval '10 minutes'");
        assertThatThrownBy(() -> service.put(jwt, DOC, "T", VersionTestSupport.doc("a"), 3))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));
    }

    @Test
    void withoutEditorRight_allThreeVerbs403() {
        lockFor(ALICE);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(authorizationService).requireDocumentRelation(ALICE, DOC, "editor");

        assertThatThrownBy(() -> service.put(jwt, DOC, "T", VersionTestSupport.doc("a"), 3))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.get(jwt, DOC))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.delete(jwt, DOC))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
        assertThat(count("SELECT count(*) FROM document_drafts")).isZero();
    }

    @Test
    void unknownOrTrashedDocument_is404() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(jwt, unknown))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.put(jwt, TRASHED, "T", Map.of(), 1))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void put_validatesBodyAndBaseVersion() {
        lockFor(ALICE);
        assertThatThrownBy(() -> service.put(jwt, DOC, "T", null, 3))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.put(jwt, DOC, "T", Map.of(), null))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
    }

    // ---------- cycle de vie / privacy ----------

    @Test
    void delete_removesOwnDraft_then404() {
        lockFor(ALICE);
        service.put(jwt, DOC, "T", VersionTestSupport.doc("a"), 3);

        service.delete(jwt, DOC);

        assertThatThrownBy(() -> service.get(jwt, DOC)).satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.delete(jwt, DOC)).satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
    }

    @Test
    void export_and_erase_coverOnlyTheUsersDrafts() {
        lockFor(ALICE);
        service.put(jwt, DOC, "Alice", VersionTestSupport.doc("a"), 3);
        currentUser = BOB;
        lockFor(BOB);
        service.put(jwt, DOC, "Bob", VersionTestSupport.doc("b"), 3);

        List<DocumentDraftService.PersonalDraftExport> export = service.exportFor(ALICE);
        assertThat(export).hasSize(1);
        assertThat(export.getFirst().title()).isEqualTo("Alice");
        assertThat(export.getFirst().documentId()).isEqualTo(DOC);

        assertThat(service.deleteAllForUser(ALICE)).isEqualTo(1);
        assertThat(service.exportFor(ALICE)).isEmpty();
        assertThat(service.exportFor(BOB)).hasSize(1);
    }

    @Test
    void draftsCascadeWithDocumentAndUser() {
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, title) VALUES (?, 'Tmp')", doc);
        jdbc.update("""
                INSERT INTO document_drafts (document_id, user_id, body, base_version_no)
                VALUES (?, ?, '{}'::jsonb, 1)
                """, doc, ALICE);
        jdbc.update("DELETE FROM documents WHERE id = ?", doc);
        assertThat(count("SELECT count(*) FROM document_drafts WHERE document_id = ?", doc)).isZero();
    }
}
