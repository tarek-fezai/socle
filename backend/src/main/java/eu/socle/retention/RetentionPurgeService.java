// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.retention;

import eu.socle.attachment.AttachmentService;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.retention.RetentionDtos.RetentionPolicy;
import eu.socle.retention.RetentionDtos.RetentionPurgeResult;
import eu.socle.storage.DocumentHistoryPurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Purge de rétention (planifiée quotidiennement par {@link RetentionPurgeScheduler}) :
 * <ol>
 *   <li>événements {@code audit_log_events} plus vieux que {@code audit_retention_months} ;</li>
 *   <li>versions {@code document_versions} hors politique (mode {@code months} | {@code count}) ;</li>
 *   <li>documents archivés ({@code status = archive}) et espaces entièrement archivés plus vieux que
 *       {@code archived_docs_retention_years} — suppression définitive (pièces jointes, historique Git).</li>
 * </ol>
 * Les ressources sous legal hold sont <strong>toujours</strong> ignorées. Idempotent : un second passage
 * sans nouvelle donnée échue renvoie des compteurs à zéro. Chaque passage est audité
 * ({@code RETENTION_PURGE_RAN}, compteurs en métadonnées).
 */
@Service
public class RetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    /** Documents supprimés par transaction → une seule réécriture Git + un GC par lot. */
    static final int DOCUMENT_BATCH_SIZE = 50;

    private final JdbcTemplate jdbc;
    private final RetentionSettingsService settings;
    private final LegalHoldService legalHolds;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate tx;
    private AttachmentService attachmentService;
    private DocumentHistoryPurgeService historyPurgeService;

    @Autowired
    public RetentionPurgeService(
            JdbcTemplate jdbc,
            RetentionSettingsService settings,
            LegalHoldService legalHolds,
            AuditService auditService,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.legalHolds = legalHolds;
        this.auditService = auditService;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Autowired(required = false)
    void setAttachmentService(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Autowired(required = false)
    void setHistoryPurgeService(DocumentHistoryPurgeService historyPurgeService) {
        this.historyPurgeService = historyPurgeService;
    }

    public RetentionPurgeResult run() {
        RetentionPolicy policy = settings.currentPolicy();
        AtomicLong skipped = new AtomicLong();
        AtomicLong failures = new AtomicLong();

        long audit = guarded("audit", failures, () -> purgeAuditEvents(policy));
        long versions = guarded("versions", failures, () -> purgeVersions(policy));
        long[] archived = {0, 0};
        try {
            archived = purgeArchived(policy, skipped, failures);
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.error("Rétention : purge des archives en échec", e);
        }

        RetentionPurgeResult result = new RetentionPurgeResult(
                audit, versions, archived[0], archived[1], skipped.get(), failures.get());
        auditService.record(null, true, AuditActions.RETENTION_PURGE_RAN,
                "instance_settings", null, result.asMetadata(), null);
        log.info("Rétention : {}", result);
        return result;
    }

    // ── 1. Journal d'audit ──────────────────────────────────────────────────

    long purgeAuditEvents(RetentionPolicy policy) {
        Long deleted = tx.execute(status -> {
            // Le trigger audit_log_events_retention_guard n'autorise le DELETE que dans cette transaction
            // et uniquement pour les lignes hors fenêtre de rétention (horloge BDD, même formule).
            jdbc.queryForObject("SELECT set_config('socle.audit_retention_purge', 'on', true)", String.class);
            return (long) jdbc.update(
                    "DELETE FROM audit_log_events WHERE created_at < now() - make_interval(months => ?)",
                    policy.auditRetentionMonths());
        });
        return deleted == null ? 0 : deleted;
    }

    // ── 2. Versions ─────────────────────────────────────────────────────────

    private static final String NOT_HELD_FOR_VERSION = """
            AND NOT EXISTS (
                SELECT 1 FROM documents d
                  JOIN legal_holds h ON h.released_at IS NULL
                   AND ((h.scope_type = 'document' AND h.scope_id = d.id)
                     OR (h.scope_type = 'space' AND h.scope_id = d.space_id))
                 WHERE d.id = v.document_id)
            AND NOT EXISTS (
                SELECT 1 FROM approval_requests ar
                 WHERE ar.document_id = v.document_id AND ar.status = 'en_cours')
            """;

    long purgeVersions(RetentionPolicy policy) {
        String mode = policy.versionRetentionMode();
        Integer value = policy.versionRetentionValue();
        if (RetentionSettingsService.MODE_UNLIMITED.equals(mode) || value == null) {
            return 0;
        }
        Long deleted = tx.execute(status -> {
            if (RetentionSettingsService.MODE_MONTHS.equals(mode)) {
                Timestamp cutoff = Timestamp.from(
                        ZonedDateTime.now(clock.withZone(ZoneOffset.UTC)).minusMonths(value).toInstant());
                return (long) jdbc.update(
                        "DELETE FROM document_versions v WHERE v.created_at < ? " + NOT_HELD_FOR_VERSION, cutoff);
            }
            // count : conserver les N versions archivées les plus récentes de chaque document.
            return (long) jdbc.update("""
                    DELETE FROM document_versions v
                     USING (SELECT id,
                                   row_number() OVER (PARTITION BY document_id ORDER BY version_no DESC) AS rn
                              FROM document_versions) ranked
                     WHERE v.id = ranked.id AND ranked.rn > ?
                    """ + NOT_HELD_FOR_VERSION, value);
        });
        return deleted == null ? 0 : deleted;
    }

    // ── 3. Archives ─────────────────────────────────────────────────────────

    /** @return {documents purgés, espaces purgés} */
    long[] purgeArchived(RetentionPolicy policy, AtomicLong skipped, AtomicLong failures) {
        Timestamp cutoff = Timestamp.from(ZonedDateTime.now(clock.withZone(ZoneOffset.UTC))
                .minusYears(policy.archivedDocsRetentionYears()).toInstant());
        long spacesPurged = 0;
        long documentsPurged = 0;

        // Espaces entièrement archivés (≥ 1 document actif, tous archivés, aucun modifié depuis le seuil).
        List<UUID> spaceIds = jdbc.query("""
                SELECT s.id FROM spaces s
                 WHERE s.deleted_at IS NULL
                   AND EXISTS (SELECT 1 FROM documents d WHERE d.space_id = s.id AND d.deleted_at IS NULL)
                   AND NOT EXISTS (SELECT 1 FROM documents d
                                    WHERE d.space_id = s.id AND d.deleted_at IS NULL AND d.status <> 'archive')
                   AND NOT EXISTS (SELECT 1 FROM documents d
                                    WHERE d.space_id = s.id AND d.updated_at >= ?)
                 ORDER BY s.created_at, s.id
                """, (rs, i) -> (UUID) rs.getObject("id"), cutoff);
        for (UUID spaceId : spaceIds) {
            if (legalHolds.isSpaceHeld(spaceId)) {
                skipped.incrementAndGet();
                continue;
            }
            try {
                Long docs = tx.execute(status -> purgeSpace(spaceId));
                spacesPurged++;
                documentsPurged += docs == null ? 0 : docs;
            } catch (RuntimeException e) {
                failures.incrementAndGet();
                log.error("Rétention : purge de l'espace archivé {} en échec", spaceId, e);
            }
        }

        // Documents archivés isolés.
        List<UUID> docIds = jdbc.query("""
                SELECT d.id FROM documents d
                 WHERE d.status = 'archive' AND d.deleted_at IS NULL AND d.updated_at < ?
                 ORDER BY d.updated_at, d.id
                """, (rs, i) -> (UUID) rs.getObject("id"), cutoff);
        Set<UUID> held = legalHolds.heldDocumentIds(docIds);
        skipped.addAndGet(held.size());
        List<UUID> eligible = new ArrayList<>(docIds);
        eligible.removeAll(held);
        for (int from = 0; from < eligible.size(); from += DOCUMENT_BATCH_SIZE) {
            List<UUID> batch = eligible.subList(from, Math.min(eligible.size(), from + DOCUMENT_BATCH_SIZE));
            try {
                tx.executeWithoutResult(status -> {
                    for (UUID id : batch) {
                        purgeDocument(id, "archived_retention");
                    }
                });
                documentsPurged += batch.size();
            } catch (RuntimeException e) {
                failures.incrementAndGet();
                log.error("Rétention : purge d'un lot de {} document(s) archivé(s) en échec", batch.size(), e);
            }
        }
        return new long[] {documentsPurged, spacesPurged};
    }

    /** Dans une transaction : supprime l'espace, ses documents (+ pièces jointes) ; Git purgé après commit. */
    private long purgeSpace(UUID spaceId) {
        List<UUID> docIds = jdbc.query(
                "SELECT id FROM documents WHERE space_id = ?", (rs, i) -> (UUID) rs.getObject("id"), spaceId);
        String name = jdbc.query("SELECT name FROM spaces WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, spaceId);
        for (UUID id : docIds) {
            purgeDocument(id, "archived_space_retention");
        }
        jdbc.update("DELETE FROM trash_items WHERE resource_type = 'space' AND resource_id = ?", spaceId);
        jdbc.update("DELETE FROM spaces WHERE id = ?", spaceId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", "retention");
        meta.put("name", name);
        meta.put("documents", docIds.size());
        auditService.record(null, true, AuditActions.SPACE_PURGED, "space", spaceId, meta, null);
        return docIds.size();
    }

    private void purgeDocument(UUID documentId, String mode) {
        legalHolds.assertDocumentNotHeld(documentId); // défense en profondeur (409 si gel posé entre-temps)
        if (attachmentService != null) {
            attachmentService.purgeForDocument(documentId, null, true);
        } else {
            jdbc.update("DELETE FROM attachments WHERE document_id = ?", documentId);
        }
        String title = jdbc.query("SELECT title FROM documents WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, documentId);
        jdbc.update("DELETE FROM trash_items WHERE resource_type = 'document' AND resource_id = ?", documentId);
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        if (historyPurgeService != null) {
            historyPurgeService.purgeAfterCommit(null, documentId);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mode", mode);
        meta.put("title", title);
        auditService.record(null, true, AuditActions.DOCUMENT_PURGED, "document", documentId, meta, null);
    }

    // ── util ────────────────────────────────────────────────────────────────

    private long guarded(String step, AtomicLong failures, LongSupplier body) {
        try {
            return body.getAsLong();
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.error("Rétention : étape « {} » en échec", step, e);
            return 0;
        }
    }
}
