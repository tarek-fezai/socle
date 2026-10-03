// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.VersionTestSupport.InMemoryVersions;
import eu.socle.storage.RelationalDocumentStore;
import eu.socle.trash.TrashService;
import eu.socle.user.UserRepository;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static eu.socle.document.VersionTestSupport.DOC;
import static eu.socle.document.VersionTestSupport.SPACE;
import static eu.socle.document.VersionTestSupport.docOf;
import static eu.socle.document.VersionTestSupport.jwt;
import static eu.socle.document.VersionTestSupport.p;
import static eu.socle.document.VersionTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Garde-fous de {@link DocumentService#restore} sur PostgreSQL réel (verrous, approbations,
 * brouillons, quatre yeux) ; authz / identité / JPA simulés, migration V35 réelle.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class DocumentRestoreRulesTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"); // restaurateur
    static final UUID BOB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");   // autre éditeur
    static final UUID CAROL = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"); // auteur du contenu courant
    static final UUID DAVE = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");  // relecteur sans contribution

    DocumentRepository documents;
    InMemoryVersions versions;
    UserSyncService userSync;
    AuthorizationService authz;
    AuditService audit;
    EditLockService locks;
    DocumentService service;
    DocumentEntity entity;
    UUID currentUser;

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
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT,
                  avatar_initials TEXT, status TEXT NOT NULL DEFAULT 'active'
                )""");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, title TEXT NOT NULL,
                  current_version_no INT NOT NULL DEFAULT 1, deleted_at TIMESTAMPTZ,
                  created_by UUID, updated_by UUID
                )""");
        jdbc.execute("""
                CREATE TABLE document_versions (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  version_no INT NOT NULL, author_id UUID
                )""");
        jdbc.execute("""
                CREATE TABLE document_edit_locks (
                  document_id UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
                  holder_user_id UUID NOT NULL REFERENCES users(id),
                  acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
        jdbc.execute("""
                CREATE TABLE approval_requests (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  status TEXT NOT NULL, requested_by UUID, submitted_version_no INT,
                  resolved_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
        // Tables lues par la construction de la réponse (vides : seul le schéma compte).
        jdbc.execute("""
                CREATE TABLE spaces (
                  id UUID PRIMARY KEY, comment_policy TEXT, default_visibility TEXT, deleted_at TIMESTAMPTZ
                )""");
        jdbc.execute("""
                CREATE TABLE space_owners (
                  space_id UUID, user_id UUID, is_responsible BOOLEAN DEFAULT false,
                  created_at TIMESTAMPTZ DEFAULT now()
                )""");
        jdbc.execute("CREATE TABLE tags (id UUID PRIMARY KEY, name TEXT, color TEXT)");
        jdbc.execute("CREATE TABLE document_tags (document_id UUID, tag_id UUID)");
        jdbc.execute("CREATE TABLE approval_role_assignments (scope_type TEXT, scope_ref TEXT)");
        // Migration réelle V35 (et non une copie) : le test échoue si elle dérive.
        String v35 = new String(
                new ClassPathResource("db/migration/V35__document_drafts.sql").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        jdbc.execute(v35);

        jdbc.update("""
                INSERT INTO users (id, email, display_name) VALUES
                  (?, 'alice@x.eu', 'Alice Martin'), (?, 'bob@x.eu', 'Bob Durand'),
                  (?, 'carol@x.eu', 'Carol Petit'), (?, 'dave@x.eu', 'Dave Leroy')
                """, ALICE, BOB, CAROL, DAVE);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document_drafts");
        jdbc.update("DELETE FROM document_edit_locks");
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM documents");
        jdbc.update("INSERT INTO documents (id, title, current_version_no, created_by, updated_by) "
                + "VALUES (?, 'Doc', 3, ?, ?)", DOC, ALICE, CAROL);

        versions = new InMemoryVersions();
        documents = mock(DocumentRepository.class);
        userSync = mock(UserSyncService.class);
        authz = mock(AuthorizationService.class);
        audit = mock(AuditService.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(BOB)).thenReturn(Optional.of(user(BOB, "Bob Durand")));
        when(userRepository.findById(ALICE)).thenReturn(Optional.of(user(ALICE, "Alice Martin")));

        EditLockProperties props = new EditLockProperties();
        props.setHeartbeatSeconds(15);
        props.setTtlSeconds(45);
        props.validate();
        locks = new EditLockService(jdbc, authz, userSync, userRepository, props, Clock.systemUTC());

        service = new DocumentService(
                documents, userSync, authz, audit, mock(ReliabilityScoreService.class),
                mock(TrashService.class), new RelationalDocumentStore(versions.repository),
                null, null, jdbc, null);
        service.setEditLockService(locks);

        // v1, v2 archivées ; v3 = contenu courant (auteur Carol)
        archived(1, docOf(List.of(p("version un"))), BOB);
        archived(2, docOf(List.of(p("version deux"))), BOB);
        entity = new DocumentEntity();
        entity.setId(DOC);
        entity.setSpaceId(SPACE);
        entity.setTitle("Doc");
        entity.setBody(docOf(List.of(p("version trois"))));
        entity.setStatus("brouillon");
        entity.setCurrentVersionNo(3);
        entity.setCreatedBy(ALICE);
        entity.setUpdatedBy(CAROL);
        when(documents.findActiveById(DOC)).thenReturn(Optional.of(entity));
        when(documents.findActiveByIdForUpdate(DOC)).thenReturn(Optional.of(entity));
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));

        currentUser = ALICE;
        when(userSync.syncFromJwt(any())).thenAnswer(inv -> user(currentUser, "U"));
    }

    private void archived(int no, Map<String, Object> body, UUID author) {
        DocumentVersionEntity v = new DocumentVersionEntity();
        v.setId(UUID.randomUUID());
        v.setDocumentId(DOC);
        v.setVersionNo(no);
        v.setBodySnapshot(body);
        v.setAuthorId(author);
        v.setArchivedBy(author);
        v.setChangeSummary("v" + no);
        v.setCreatedAt(java.time.Instant.now());
        versions.rows.add(v);
    }

    private static void assertStatus(Throwable t, HttpStatus expected) {
        assertThat(t).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    private void lockHeldBy(UUID holder, String heartbeatAgo) {
        jdbc.update("INSERT INTO document_edit_locks (document_id, holder_user_id, acquired_at, heartbeat_at) "
                + "VALUES (?, ?, now() - interval '" + heartbeatAgo + "', now() - interval '" + heartbeatAgo + "')",
                DOC, holder);
    }

    // --- a) éditeur requis ; 404 si version absente -------------------------------------------------

    @Test
    void a_editorRequired_403_andNothingWritten() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé"))
                .when(authz).requireDocumentRelation(ALICE, DOC, "editor");

        assertThatThrownBy(() -> service.restore(jwt(), DOC, 1, 3))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));

        assertThat(versions.rows).hasSize(2);
        assertThat(entity.getCurrentVersionNo()).isEqualTo(3);
    }

    @Test
    void a_missingVersion_404_beforeAnyOtherRule() {
        lockHeldBy(BOB, "1 second");                      // serait 409 si la version existait
        jdbc.update("INSERT INTO approval_requests (document_id, status) VALUES (?, 'en_cours')", DOC);

        assertThatThrownBy(() -> service.restore(jwt(), DOC, 99, 3))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        // La version courante n'est pas archivée : restaurer v3 → 404 également.
        assertThatThrownBy(() -> service.restore(jwt(), DOC, 3, 3))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));

        assertThat(versions.rows).hasSize(2);
        assertThat(entity.getCurrentVersionNo()).isEqualTo(3);
    }

    // --- b) verrou d'un autre utilisateur ---------------------------------------------------------

    @Test
    void b_lockHeldByAnotherUser_409_withHolderName() {
        lockHeldBy(BOB, "1 second");

        assertThatThrownBy(() -> service.restore(jwt(), DOC, 1, 3))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT))
                .hasMessageContaining("Bob Durand");

        assertThat(versions.rows).hasSize(2);
        assertThat(entity.getCurrentVersionNo()).isEqualTo(3);
    }

    @Test
    void b_ownLock_noLock_andExpiredLock_areAllowed() {
        // Verrou du restaurateur
        lockHeldBy(ALICE, "1 second");
        assertThat(service.restore(jwt(), DOC, 1, 3).currentVersionNo()).isEqualTo(4);

        // Aucun verrou
        jdbc.update("DELETE FROM document_edit_locks");
        assertThat(service.restore(jwt(), DOC, 1, 4).currentVersionNo()).isEqualTo(5);

        // Verrou d'un autre utilisateur mais expiré (heartbeat hors TTL)
        lockHeldBy(BOB, "10 minutes");
        assertThat(service.restore(jwt(), DOC, 1, 5).currentVersionNo()).isEqualTo(6);
    }

    // --- c) demande d'approbation en cours ---------------------------------------------------------

    @Test
    void c_approvalInProgress_409() {
        jdbc.update("INSERT INTO approval_requests (document_id, status, requested_by) VALUES (?, 'en_cours', ?)",
                DOC, BOB);

        assertThatThrownBy(() -> service.restore(jwt(), DOC, 1, 3))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT))
                .hasMessageContaining("Demande d'approbation en cours");

        assertThat(versions.rows).hasSize(2);
        assertThat(entity.getCurrentVersionNo()).isEqualTo(3);
    }

    @Test
    void c_resolvedApproval_doesNotBlock() {
        jdbc.update("INSERT INTO approval_requests (document_id, status, requested_by) VALUES (?, 'approuve', ?)",
                DOC, BOB);
        jdbc.update("INSERT INTO approval_requests (document_id, status, requested_by) VALUES (?, 'rejete', ?)",
                DOC, BOB);

        assertThat(service.restore(jwt(), DOC, 1, 3).currentVersionNo()).isEqualTo(4);
    }

    // --- d) quatre yeux ---------------------------------------------------------------------------

    @Test
    void d_restorerBecomesContributor_andCannotDecide_whileNonContributorCan() {
        currentUser = ALICE;
        service.restore(jwt(), DOC, 1, 3);

        // L'ancien contenu courant est archivé avec SON auteur (Carol), pas avec le restaurateur.
        DocumentVersionEntity archivedPrevious = versions.rows.stream()
                .filter(v -> v.getVersionNo() == 3).findFirst().orElseThrow();
        assertThat(archivedPrevious.getAuthorId()).isEqualTo(CAROL);
        assertThat(archivedPrevious.getArchivedBy()).isEqualTo(ALICE);
        // Le nouveau contenu courant est signé par le restaurateur.
        assertThat(entity.getUpdatedBy()).isEqualTo(ALICE);

        // Reflet en base de ce que la transaction a écrit (JPA simulé), puis règle quatre yeux réelle.
        for (DocumentVersionEntity v : versions.rows) {
            jdbc.update("INSERT INTO document_versions (document_id, version_no, author_id) VALUES (?, ?, ?)",
                    DOC, v.getVersionNo(), v.getAuthorId());
        }
        jdbc.update("UPDATE documents SET current_version_no = ?, updated_by = ? WHERE id = ?",
                entity.getCurrentVersionNo(), entity.getUpdatedBy(), DOC);

        // Alice (restauratrice) démarre l'approbation.
        jdbc.update("INSERT INTO approval_requests (document_id, status, requested_by, submitted_version_no) "
                + "VALUES (?, 'en_cours', ?, ?)", DOC, ALICE, entity.getCurrentVersionNo());

        Set<UUID> contributors = FourEyesPolicy.loadContentContributors(jdbc, DOC);
        assertThat(contributors).contains(ALICE, CAROL);
        assertThat(contributors).doesNotContain(DAVE);
        assertThat(FourEyesPolicy.isConflict(ALICE, ALICE, contributors)).isTrue();   // A ne peut pas décider
        assertThat(FourEyesPolicy.isConflict(DAVE, ALICE, contributors)).isFalse();   // B (non contributeur) peut
    }

    @Test
    void d_restorerIsContributorEvenWhenSomeoneElseRequestsApproval() {
        service.restore(jwt(), DOC, 2, 3);
        jdbc.update("UPDATE documents SET current_version_no = ?, updated_by = ? WHERE id = ?",
                entity.getCurrentVersionNo(), entity.getUpdatedBy(), DOC);

        Set<UUID> contributors = FourEyesPolicy.loadContentContributors(jdbc, DOC);
        // Bob demande l'approbation : Alice (restauratrice, updated_by) reste en conflit.
        assertThat(FourEyesPolicy.isConflict(ALICE, BOB, contributors)).isTrue();
        assertThat(FourEyesPolicy.isConflict(DAVE, BOB, contributors)).isFalse();
    }

    // --- e) append-only ---------------------------------------------------------------------------

    @Test
    void e_createsNextVersionAppendOnly_withRestorationSummary_andAuditsKept() {
        var before = List.copyOf(versions.rows);

        var response = service.restore(jwt(), DOC, 1, 3);

        assertThat(response.currentVersionNo()).isEqualTo(4);
        assertThat(response.body()).isEqualTo(before.getFirst().getBodySnapshot());
        // v1 et v2 inchangées ; v3 (ancien courant) archivée avec le résumé de restauration
        assertThat(versions.rows).hasSize(3);
        assertThat(versions.rows.subList(0, 2)).containsExactlyElementsOf(before);
        assertThat(versions.rows.get(0).getChangeSummary()).isEqualTo("v1");
        DocumentVersionEntity v3 = versions.rows.get(2);
        assertThat(v3.getVersionNo()).isEqualTo(3);
        assertThat(v3.getChangeSummary()).isEqualTo("Restauration de la version 1");
        assertThat(v3.getBodySnapshot()).isEqualTo(docOf(List.of(p("version trois"))));
        verify(versions.repository, never()).delete(any());
        verify(versions.repository, never()).deleteAll();

        verify(audit).record(eq(ALICE), eq(false), eq(AuditActions.DOCUMENT_VERSION_CREATED),
                eq("document"), eq(DOC), anyMap(), isNull());
        verify(audit).record(eq(ALICE), eq(false), eq(AuditActions.DOCUMENT_VERSION_RESTORED),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    // --- f) brouillons ----------------------------------------------------------------------------

    @Test
    void f_restorerDraftIsDiscarded_otherUsersDraftsUntouched() {
        jdbc.update("INSERT INTO document_drafts (document_id, user_id, body, base_version_no) "
                + "VALUES (?, ?, '{}'::jsonb, 3)", DOC, ALICE);
        jdbc.update("INSERT INTO document_drafts (document_id, user_id, body, base_version_no) "
                + "VALUES (?, ?, '{}'::jsonb, 3)", DOC, BOB);

        service.restore(jwt(), DOC, 1, 3);

        List<UUID> remaining = jdbc.query("SELECT user_id FROM document_drafts WHERE document_id = ?",
                (rs, i) -> (UUID) rs.getObject(1), DOC);
        assertThat(remaining).containsExactly(BOB);
    }

    @Test
    void f_failedRestore_keepsRestorerDraft() {
        jdbc.update("INSERT INTO document_drafts (document_id, user_id, body, base_version_no) "
                + "VALUES (?, ?, '{}'::jsonb, 3)", DOC, ALICE);
        lockHeldBy(BOB, "1 second");

        assertThatThrownBy(() -> service.restore(jwt(), DOC, 1, 3))
                .satisfies(t -> assertStatus(t, HttpStatus.CONFLICT));

        Integer drafts = jdbc.queryForObject(
                "SELECT count(*) FROM document_drafts WHERE document_id = ? AND user_id = ?",
                Integer.class, DOC, ALICE);
        assertThat(drafts).isEqualTo(1);
    }
}
