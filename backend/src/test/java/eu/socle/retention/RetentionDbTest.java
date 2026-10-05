// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.retention;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.config.SocleProperties;
import eu.socle.identity.IdentityFacade;
import eu.socle.retention.LegalHoldDtos.LegalHoldView;
import eu.socle.retention.LegalHoldDtos.PlaceLegalHoldRequest;
import eu.socle.retention.LegalHoldDtos.ReleaseLegalHoldRequest;
import eu.socle.retention.RetentionDtos.RetentionPurgeResult;
import eu.socle.retention.RetentionDtos.RetentionSettingsView;
import eu.socle.retention.RetentionDtos.UpdateRetentionRequest;
import eu.socle.testsupport.MigratedPostgres;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rétention, gel légal et purge planifiée contre le <strong>schéma réel</strong> (Flyway V1…V43) :
 * valide la migration V43, la garde de suppression de {@code audit_log_events} et les compteurs.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("eu.socle.testsupport.MigratedPostgres#dockerAvailable")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetentionDbTest {

    @Container
    static PostgreSQLContainer<?> postgres = MigratedPostgres.newContainer();

    static JdbcTemplate jdbc;

    static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID MEMBER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock IdentityFacade identityFacade;
    @Mock AuditService auditService;

    LegalHoldService holds;
    RetentionSettingsService settings;
    RetentionPurgeService purge;
    Jwt jwt;

    UUID space;
    UUID doc;

    @BeforeAll
    static void migrate() {
        jdbc = MigratedPostgres.migrate(postgres);
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'admin@example.com', 'Admin')", ADMIN);
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'member@example.com', 'Member')", MEMBER);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM legal_holds");
        jdbc.update("DELETE FROM trash_items");
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM document_comments");
        jdbc.update("DELETE FROM document_drafts");
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM attachments");
        jdbc.update("DELETE FROM documents");
        jdbc.update("DELETE FROM spaces");
        jdbc.update("""
                UPDATE instance_settings
                   SET audit_retention_months = 24, version_retention_mode = 'unlimited',
                       version_retention_value = NULL, archived_docs_retention_years = 7,
                       processing_register_reviewed_at = NULL
                 WHERE id = true
                """);

        UserEntity admin = new UserEntity();
        admin.setId(ADMIN);
        admin.setEmail("admin@example.com");
        admin.setDisplayName("Admin");
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        when(identityFacade.sync(any())).thenReturn(admin);
        jwt = Jwt.withTokenValue("t").header("alg", "none").subject(ADMIN.toString())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();

        holds = new LegalHoldService(jdbc, identityFacade, auditService);
        SocleProperties props = new SocleProperties(null, null, null,
                new SocleProperties.Instance("Test", "UE – Paris", null), null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<eu.socle.storage.GitPurgeQueueService> queueProvider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(queueProvider.getIfAvailable()).thenReturn(null);
        settings = new RetentionSettingsService(jdbc, identityFacade, auditService, props, CLOCK, queueProvider);
        purge = new RetentionPurgeService(jdbc, settings, holds, auditService, CLOCK,
                new DataSourceTransactionManager(jdbc.getDataSource()));

        space = newSpace("Espace");
        doc = newDocument(space, "Doc", "brouillon", NOW);
    }

    // ── V43 ─────────────────────────────────────────────────────────────────

    @Test
    void v43_defaultsAndSingletonRows() {
        assertThat(jdbc.queryForObject("SELECT audit_retention_months FROM instance_settings", Integer.class))
                .isEqualTo(24);
        assertThat(jdbc.queryForObject("SELECT archived_docs_retention_years FROM instance_settings", Integer.class))
                .isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT version_retention_mode FROM instance_settings", String.class))
                .isEqualTo("unlimited");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM branding_settings", Integer.class)).isEqualTo(1);
    }

    @Test
    void v43_rejectsInconsistentVersionRetention() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE instance_settings SET version_retention_mode = 'months', version_retention_value = NULL"))
                .hasMessageContaining("instance_settings_version_retention_value_chk");
        assertThatThrownBy(() -> jdbc.update("UPDATE instance_settings SET version_retention_mode = 'forever'"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void auditDeleteRefusedOutsideRetentionPurge() {
        jdbc.update("INSERT INTO audit_log_events (actor_is_system, action, resource_type, created_at) "
                + "VALUES (true, 'x.old', 'x', now() - interval '60 months')");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log_events WHERE action = 'x.old'"))
                .rootCause().hasMessageContaining("retention");
        assertThat(count("SELECT count(*) FROM audit_log_events WHERE action = 'x.old'")).isEqualTo(1);
    }

    // ── Legal hold ──────────────────────────────────────────────────────────

    @Test
    void placeHold_requiresReason() {
        for (String reason : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> holds.place(jwt, new PlaceLegalHoldRequest("document", doc, reason)))
                    .isInstanceOf(CodedStatusException.class)
                    .extracting(e -> ((CodedStatusException) e).getCode())
                    .isEqualTo(ApiErrors.LEGAL_HOLD_REASON_REQUIRED);
        }
        assertThat(count("SELECT count(*) FROM legal_holds")).isZero();
    }

    @Test
    void placeHold_requiresSystemAdmin() {
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        assertThatThrownBy(() -> holds.place(jwt, new PlaceLegalHoldRequest("document", doc, "litige")))
                .hasMessageContaining("Administrateur");
        assertThat(count("SELECT count(*) FROM legal_holds")).isZero();
    }

    @Test
    void placeHold_unknownScope_404() {
        assertThatThrownBy(() -> holds.place(jwt, new PlaceLegalHoldRequest("document", UUID.randomUUID(), "x")))
                .hasMessageContaining("404");
    }

    @Test
    void placeAndRelease_areAudited_andUniquePerScope() {
        LegalHoldView placed = holds.place(jwt, new PlaceLegalHoldRequest("document", doc, "Litige X"));
        assertThat(placed.active()).isTrue();
        assertThat(placed.reason()).isEqualTo("Litige X");
        assertThat(placed.scopeLabel()).isEqualTo("Doc");
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.LEGAL_HOLD_PLACED),
                eq("legal_hold"), eq(placed.id()), anyMap(), isNull());

        assertThatThrownBy(() -> holds.place(jwt, new PlaceLegalHoldRequest("document", doc, "encore")))
                .isInstanceOf(CodedStatusException.class)
                .extracting(e -> ((CodedStatusException) e).getCode())
                .isEqualTo(ApiErrors.LEGAL_HOLD_ALREADY_ACTIVE);

        LegalHoldView released = holds.release(jwt, placed.id(), new ReleaseLegalHoldRequest("Clos"));
        assertThat(released.active()).isFalse();
        assertThat(released.releasedBy()).isEqualTo(ADMIN);
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.LEGAL_HOLD_RELEASED),
                eq("legal_hold"), eq(placed.id()), anyMap(), isNull());

        // Après libération, un nouveau gel est possible (index unique partiel sur les gels actifs).
        assertThat(holds.place(jwt, new PlaceLegalHoldRequest("document", doc, "Nouveau")).active()).isTrue();
        assertThat(holds.list(jwt, true).holds()).hasSize(1);
        assertThat(holds.list(jwt, false).holds()).hasSize(2);
        assertThat(holds.list(jwt, true).activeCount()).isEqualTo(1);
    }

    @Test
    void documentHold_blocksThatDocumentOnly() {
        UUID other = newDocument(space, "Autre", "brouillon", NOW);
        holds.place(jwt, new PlaceLegalHoldRequest("document", doc, "Litige"));

        assertLegalHold(() -> holds.assertDocumentNotHeld(doc));
        holds.assertDocumentNotHeld(other);
        assertLegalHold(() -> holds.assertDocumentsNotHeld(List.of(other, doc)));
        assertThat(holds.heldDocumentIds(List.of(doc, other))).containsExactly(doc);
        // Un document gelé empêche aussi la purge de son espace.
        assertLegalHold(() -> holds.assertSpaceNotHeld(space));
    }

    @Test
    void spaceHold_coversItsDocuments() {
        UUID other = newDocument(space, "Autre", "brouillon", NOW);
        UUID elsewhere = newDocument(newSpace("Ailleurs"), "Hors", "brouillon", NOW);
        holds.place(jwt, new PlaceLegalHoldRequest("space", space, "Enquête"));

        assertLegalHold(() -> holds.assertDocumentNotHeld(doc));
        assertLegalHold(() -> holds.assertDocumentNotHeld(other));
        holds.assertDocumentNotHeld(elsewhere);
        assertThat(holds.heldDocumentIds(List.of(doc, other, elsewhere))).containsExactlyInAnyOrder(doc, other);
        assertLegalHold(() -> holds.assertSpaceNotHeld(space));
    }

    @Test
    void releasedHold_noLongerBlocks() {
        LegalHoldView h = holds.place(jwt, new PlaceLegalHoldRequest("document", doc, "Litige"));
        holds.release(jwt, h.id(), new ReleaseLegalHoldRequest("Clos"));
        holds.assertDocumentNotHeld(doc);
        assertThat(holds.isDocumentHeld(doc)).isFalse();
    }

    @Test
    void erasureOfUserWithCommentOnHeldDocument_isBlocked() {
        jdbc.update("INSERT INTO document_comments (document_id, author_id, body) VALUES (?, ?, 'hello')",
                doc, MEMBER);
        holds.assertUserDataNotHeld(MEMBER);
        holds.place(jwt, new PlaceLegalHoldRequest("document", doc, "Litige"));
        assertLegalHold(() -> holds.assertUserDataNotHeld(MEMBER));
        holds.assertUserDataNotHeld(ADMIN); // aucune donnée sur un document gelé
    }

    // ── Réglages de rétention ───────────────────────────────────────────────

    @Test
    void settings_getReturnsDefaultsAndResidenceLabel() {
        RetentionSettingsView v = settings.get(jwt);
        assertThat(v.auditRetentionMonths()).isEqualTo(24);
        assertThat(v.versionRetentionMode()).isEqualTo("unlimited");
        assertThat(v.archivedDocsRetentionYears()).isEqualTo(7);
        assertThat(v.dataResidenceLabel()).isEqualTo("UE – Paris");
        assertThat(v.activeLegalHolds()).isZero();
    }

    @Test
    void settings_updateIsValidatedAndAudited() {
        RetentionSettingsView v = settings.update(jwt,
                new UpdateRetentionRequest(36, "months", 12, 10, LocalDate.of(2026, 9, 1)));
        assertThat(v.auditRetentionMonths()).isEqualTo(36);
        assertThat(v.versionRetentionMode()).isEqualTo("months");
        assertThat(v.versionRetentionValue()).isEqualTo(12);
        assertThat(v.archivedDocsRetentionYears()).isEqualTo(10);
        assertThat(v.processingRegisterReviewedAt()).isEqualTo(LocalDate.of(2026, 9, 1));

        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.RETENTION_SETTINGS_UPDATED),
                eq("instance_settings"), isNull(), anyMap(), isNull());
        verify(auditService).record(eq(ADMIN), eq(false), eq(AuditActions.RETENTION_PROCESSING_REGISTER_REVIEWED),
                eq("instance_settings"), isNull(), anyMap(), isNull());

        // « unlimited » efface la valeur.
        v = settings.update(jwt, new UpdateRetentionRequest(36, "unlimited", 5, 10, LocalDate.of(2026, 9, 1)));
        assertThat(v.versionRetentionValue()).isNull();
    }

    @Test
    void settings_invalidValuesRejected() {
        UpdateRetentionRequest[] invalid = {
                new UpdateRetentionRequest(0, "unlimited", null, 7, null),
                new UpdateRetentionRequest(24, "weekly", null, 7, null),
                new UpdateRetentionRequest(24, "months", null, 7, null),
                new UpdateRetentionRequest(24, "count", 0, 7, null),
                new UpdateRetentionRequest(24, "unlimited", null, 0, null),
                new UpdateRetentionRequest(24, "unlimited", null, 7, LocalDate.of(2099, 1, 1)),
        };
        for (UpdateRetentionRequest r : invalid) {
            assertThatThrownBy(() -> settings.update(jwt, r)).hasMessageContaining("400");
        }
        assertThat(settings.currentPolicy().auditRetentionMonths()).isEqualTo(24);
    }

    @Test
    void settings_requireSystemAdmin() {
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        assertThatThrownBy(() -> settings.get(jwt)).hasMessageContaining("Administrateur");
    }

    // ── Purge planifiée ─────────────────────────────────────────────────────

    @Test
    void purge_auditEventsOlderThanRetention_only() {
        insertAudit("old.event", "now() - interval '30 months'");
        insertAudit("old.event2", "now() - interval '25 months'");
        insertAudit("recent.event", "now() - interval '23 months'");

        RetentionPurgeResult r = purge.run();

        assertThat(r.auditEventsDeleted()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_log_events WHERE action LIKE 'old.%'")).isZero();
        assertThat(count("SELECT count(*) FROM audit_log_events WHERE action = 'recent.event'")).isEqualTo(1);

        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(isNull(), eq(true), eq(AuditActions.RETENTION_PURGE_RAN),
                eq("instance_settings"), isNull(), meta.capture(), isNull());
        assertThat(meta.getValue()).containsEntry("auditEventsDeleted", 2L).containsEntry("failures", 0L);

        // Idempotent : un second passage ne supprime plus rien.
        assertThat(purge.run().auditEventsDeleted()).isZero();
    }

    @Test
    void purge_versionsByMonths_keepsRecentHeldAndPendingApproval() {
        settings.update(jwt, new UpdateRetentionRequest(24, "months", 6, 7, null));
        UUID held = newDocument(space, "Gelé", "brouillon", NOW);
        UUID pending = newDocument(space, "En revue", "en_revue", NOW);
        holds.place(jwt, new PlaceLegalHoldRequest("document", held, "Litige"));
        insertApproval(pending);

        newVersion(doc, 1, "now() - interval '12 months'");
        newVersion(doc, 2, "now() - interval '1 month'");
        newVersion(held, 1, "now() - interval '12 months'");
        newVersion(pending, 1, "now() - interval '12 months'");

        RetentionPurgeResult r = purge.run();

        assertThat(r.versionsDeleted()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document_versions WHERE document_id = '" + doc + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document_versions WHERE document_id = '" + held + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document_versions WHERE document_id = '" + pending + "'")).isEqualTo(1);
        assertThat(purge.run().versionsDeleted()).isZero();
    }

    @Test
    void purge_versionsByCount_keepsNewestN() {
        settings.update(jwt, new UpdateRetentionRequest(24, "count", 2, 7, null));
        for (int v = 1; v <= 5; v++) {
            newVersion(doc, v, "now() - interval '1 day'");
        }

        assertThat(purge.run().versionsDeleted()).isEqualTo(3);
        assertThat(jdbc.queryForList(
                "SELECT version_no FROM document_versions WHERE document_id = ? ORDER BY version_no", Integer.class, doc))
                .containsExactly(4, 5);
        assertThat(purge.run().versionsDeleted()).isZero();
    }

    @Test
    void purge_unlimitedVersions_deletesNothing() {
        newVersion(doc, 1, "now() - interval '100 months'");
        assertThat(purge.run().versionsDeleted()).isZero();
        assertThat(count("SELECT count(*) FROM document_versions")).isEqualTo(1);
    }

    @Test
    void purge_archivedDocuments_olderThanRetention_skippingHeld() {
        UUID oldArchived = newDocument(space, "Vieux", "archive", NOW.minusSeconds(8L * 365 * 86400));
        UUID heldArchived = newDocument(space, "Gelé archive", "archive", NOW.minusSeconds(8L * 365 * 86400));
        UUID recentArchived = newDocument(space, "Récent archive", "archive", NOW.minusSeconds(2L * 365 * 86400));
        holds.place(jwt, new PlaceLegalHoldRequest("document", heldArchived, "Litige"));
        newVersion(oldArchived, 1, "now()");

        RetentionPurgeResult r = purge.run();

        assertThat(r.archivedDocumentsPurged()).isEqualTo(1);
        assertThat(r.skippedLegalHold()).isEqualTo(1);
        assertThat(exists(oldArchived)).isFalse();
        assertThat(exists(heldArchived)).isTrue();
        assertThat(exists(recentArchived)).isTrue();
        assertThat(exists(doc)).isTrue(); // brouillon : jamais purgé par rétention
        assertThat(count("SELECT count(*) FROM document_versions WHERE document_id = '" + oldArchived + "'")).isZero();
        verify(auditService).record(isNull(), eq(true), eq(AuditActions.DOCUMENT_PURGED), eq("document"),
                eq(oldArchived), anyMap(), isNull());

        // Idempotent : le gel retient toujours le document, rien d'autre ne part.
        RetentionPurgeResult second = purge.run();
        assertThat(second.archivedDocumentsPurged()).isZero();
        assertThat(exists(heldArchived)).isTrue();
    }

    @Test
    void purge_archivedSpace_olderThanRetention_andHeldSpaceSkipped() {
        Instant old = NOW.minusSeconds(8L * 365 * 86400);
        UUID archivedSpace = newSpace("Archivé");
        UUID d1 = newDocument(archivedSpace, "A1", "archive", old);
        UUID d2 = newDocument(archivedSpace, "A2", "archive", old);
        UUID heldSpace = newSpace("Gelé");
        newDocument(heldSpace, "H1", "archive", old);
        holds.place(jwt, new PlaceLegalHoldRequest("space", heldSpace, "Enquête"));
        UUID liveSpace = newSpace("Vivant");
        newDocument(liveSpace, "V1", "archive", old);
        newDocument(liveSpace, "V2", "brouillon", NOW);

        RetentionPurgeResult r = purge.run();

        assertThat(r.archivedSpacesPurged()).isEqualTo(1);
        assertThat(r.skippedLegalHold()).isGreaterThanOrEqualTo(1);
        assertThat(spaceExists(archivedSpace)).isFalse();
        assertThat(exists(d1)).isFalse();
        assertThat(exists(d2)).isFalse();
        assertThat(spaceExists(heldSpace)).isTrue();
        assertThat(spaceExists(liveSpace)).isTrue();
        verify(auditService).record(isNull(), eq(true), eq(AuditActions.SPACE_PURGED), eq("space"),
                eq(archivedSpace), anyMap(), isNull());
        verify(auditService, never()).record(any(), eq(true), eq(AuditActions.SPACE_PURGED), eq("space"),
                eq(heldSpace), anyMap(), any());
    }

    @Test
    void purge_alwaysAuditsRun() {
        purge.run();
        verify(auditService, atLeastOnce()).record(isNull(), eq(true), eq(AuditActions.RETENTION_PURGE_RAN),
                eq("instance_settings"), isNull(), anyMap(), isNull());
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    static void assertLegalHold(Runnable r) {
        assertThatThrownBy(r::run)
                .isInstanceOf(CodedStatusException.class)
                .satisfies(e -> {
                    CodedStatusException c = (CodedStatusException) e;
                    assertThat(c.getCode()).isEqualTo(ApiErrors.LEGAL_HOLD_ACTIVE);
                    assertThat(c.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                });
    }

    static long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    static boolean exists(UUID documentId) {
        return count("SELECT count(*) FROM documents WHERE id = '" + documentId + "'") > 0;
    }

    static boolean spaceExists(UUID spaceId) {
        return count("SELECT count(*) FROM spaces WHERE id = '" + spaceId + "'") > 0;
    }

    static UUID newSpace(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    static UUID newDocument(UUID spaceId, String title, String status, Instant updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, status, created_by, updated_at)
                VALUES (?, ?, ?, '{"type":"doc","content":[]}'::jsonb, ?, ?, ?)
                """, id, spaceId, title, status, ADMIN, java.sql.Timestamp.from(updatedAt));
        return id;
    }

    static void newVersion(UUID documentId, int no, String createdAtSql) {
        jdbc.update("INSERT INTO document_versions (document_id, version_no, body_snapshot, created_at) "
                + "VALUES (?, ?, '{\"type\":\"doc\"}'::jsonb, " + createdAtSql + ")", documentId, no);
    }

    static void insertAudit(String action, String createdAtSql) {
        jdbc.update("INSERT INTO audit_log_events (actor_is_system, action, resource_type, created_at) "
                + "VALUES (true, ?, 'x', " + createdAtSql + ")", action);
    }

    static void insertApproval(UUID documentId) {
        UUID wf = UUID.randomUUID();
        jdbc.update("INSERT INTO approval_workflows (id, name) VALUES (?, 'wf')", wf);
        jdbc.update("""
                INSERT INTO approval_requests (document_id, workflow_id, temporal_workflow_id, requested_by, status)
                VALUES (?, ?, ?, ?, 'en_cours')
                """, documentId, wf, "wf-" + documentId, ADMIN);
    }
}
