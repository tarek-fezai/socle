// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backfill idempotent de {@code document_versions.author_id} / {@code archived_by}
 * et {@code documents.updated_by} depuis l'ancienne sémantique
 * (author_id = qui a remplacé la version).
 * Journalisé dans {@code authz_migrations} sous {@value BACKFILL_NAME}.
 */
@Service
public class VersionAuthorshipBackfillService {

    public static final String BACKFILL_NAME = "version-authorship-v1";
    public static final String SUBMISSION_SUMMARY = "Soumission pour approbation";

    private static final Logger log = LoggerFactory.getLogger(VersionAuthorshipBackfillService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public VersionAuthorshipBackfillService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean isApplied() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM authz_migrations WHERE name = ?",
                Integer.class,
                BACKFILL_NAME);
        return n != null && n > 0;
    }

    @Transactional
    public BackfillReport backfill(boolean force) {
        if (!force && isApplied()) {
            log.info("Backfill {} déjà appliqué — skip", BACKFILL_NAME);
            return BackfillReport.skippedReport();
        }

        List<Map<String, Object>> docs = jdbc.queryForList("""
                SELECT id, created_by, updated_by, current_version_no
                  FROM documents
                 WHERE deleted_at IS NULL
                """);

        int scannedVersions = 0;
        int corrected = 0;
        int updatedByFixed = 0;
        List<IndeterminateRow> indeterminate = new ArrayList<>();

        for (Map<String, Object> doc : docs) {
            UUID docId = (UUID) doc.get("id");
            UUID createdBy = (UUID) doc.get("created_by");

            List<Map<String, Object>> versions = jdbc.queryForList("""
                    SELECT version_no, author_id, archived_by, change_summary
                      FROM document_versions
                     WHERE document_id = ?
                     ORDER BY version_no ASC
                    """, docId);

            Map<Integer, UUID> realAuthors = new HashMap<>();
            Map<Integer, UUID> oldAuthors = new HashMap<>();

            for (Map<String, Object> v : versions) {
                scannedVersions++;
                int versionNo = ((Number) v.get("version_no")).intValue();
                UUID oldAuthor = (UUID) v.get("author_id");
                oldAuthors.put(versionNo, oldAuthor);

                UUID realAuthor;
                String reason = null;
                if (versionNo == 1) {
                    realAuthor = createdBy;
                    if (realAuthor == null) {
                        reason = "v1 sans documents.created_by";
                    }
                } else if (SUBMISSION_SUMMARY.equals(v.get("change_summary"))) {
                    realAuthor = realAuthors.get(versionNo - 1);
                    if (realAuthor == null && !oldAuthors.containsKey(versionNo - 1)) {
                        reason = "soumission sans version précédente";
                    } else if (realAuthor == null) {
                        reason = "auteur réel de v" + (versionNo - 1) + " indéterminable";
                    }
                } else {
                    UUID prevOld = oldAuthors.get(versionNo - 1);
                    if (prevOld == null && !oldAuthors.containsKey(versionNo - 1)) {
                        realAuthor = null;
                        reason = "version " + (versionNo - 1) + " absente";
                    } else {
                        realAuthor = prevOld;
                        if (realAuthor == null) {
                            reason = "author_id historique de v" + (versionNo - 1) + " NULL";
                        }
                    }
                }

                realAuthors.put(versionNo, realAuthor);
                UUID archivedBy = oldAuthor;

                jdbc.update("""
                        UPDATE document_versions
                           SET author_id = ?, archived_by = ?
                         WHERE document_id = ? AND version_no = ?
                        """,
                        realAuthor, archivedBy, docId, versionNo);
                corrected++;

                if (realAuthor == null) {
                    indeterminate.add(new IndeterminateRow(docId, versionNo, reason));
                }
            }

            // updated_by = auteur du contenu courant
            UUID newUpdatedBy = resolveCurrentContentAuthor(
                    createdBy, versions, realAuthors, oldAuthors);
            if (newUpdatedBy != null) {
                int n = jdbc.update(
                        "UPDATE documents SET updated_by = ? WHERE id = ?",
                        newUpdatedBy, docId);
                if (n > 0) {
                    updatedByFixed++;
                }
            }
        }

        BackfillReport report = new BackfillReport(
                false,
                docs.size(),
                scannedVersions,
                corrected,
                updatedByFixed,
                List.copyOf(indeterminate));
        persist(report);
        log.info("Backfill {} : docs={} versions={} corrected={} updated_by={} indeterminate={}",
                BACKFILL_NAME, report.documentsScanned(), report.versionsScanned(),
                report.versionsCorrected(), report.updatedByFixed(),
                report.indeterminate().size());
        return report;
    }

    /**
     * Contenu courant : si la dernière archive est une soumission → auteur réel de cette version ;
     * sinon l'ancien {@code author_id} de la dernière archive = qui a écrit le contenu courant.
     */
    static UUID resolveCurrentContentAuthor(
            UUID createdBy,
            List<Map<String, Object>> versions,
            Map<Integer, UUID> realAuthors,
            Map<Integer, UUID> oldAuthors
    ) {
        if (versions == null || versions.isEmpty()) {
            return createdBy;
        }
        Map<String, Object> last = versions.getLast();
        int lastNo = ((Number) last.get("version_no")).intValue();
        if (SUBMISSION_SUMMARY.equals(last.get("change_summary"))) {
            return realAuthors.get(lastNo);
        }
        UUID old = oldAuthors.get(lastNo);
        return old != null ? old : realAuthors.get(lastNo);
    }

    private void persist(BackfillReport report) {
        try {
            String json = objectMapper.writeValueAsString(report.toMap());
            jdbc.update("""
                    INSERT INTO authz_migrations (name, applied_at, report)
                    VALUES (?, now(), ?::jsonb)
                    ON CONFLICT (name) DO UPDATE
                       SET applied_at = excluded.applied_at,
                           report = excluded.report
                    """,
                    BACKFILL_NAME, json);
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de journaliser " + BACKFILL_NAME, e);
        }
    }

    public record IndeterminateRow(UUID documentId, int versionNo, String reason) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentId", documentId != null ? documentId.toString() : null);
            m.put("versionNo", versionNo);
            m.put("reason", reason);
            return m;
        }
    }

    public record BackfillReport(
            boolean skipped,
            int documentsScanned,
            int versionsScanned,
            int versionsCorrected,
            int updatedByFixed,
            List<IndeterminateRow> indeterminate
    ) {
        static BackfillReport skippedReport() {
            return new BackfillReport(true, 0, 0, 0, 0, List.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", BACKFILL_NAME);
            m.put("skipped", skipped);
            m.put("documentsScanned", documentsScanned);
            m.put("versionsScanned", versionsScanned);
            m.put("versionsCorrected", versionsCorrected);
            m.put("updatedByFixed", updatedByFixed);
            m.put("indeterminateCount", indeterminate.size());
            m.put("indeterminate", indeterminate.stream().map(IndeterminateRow::toMap).toList());
            return m;
        }
    }
}
