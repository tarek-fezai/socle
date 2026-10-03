// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Corrige le décalage historique : le résumé écrit pour vN+1 était stocké sur
 * l'archive vN. Décale {@code change_summary} de N vers N+1 ; le résumé de la
 * dernière archive → {@code documents.current_change_summary} ; v1 → NULL.
 * Journalisé dans {@code authz_migrations} sous {@value BACKFILL_NAME}.
 *
 * <p>Réservé au provider relational (voir {@link VersionChangeSummaryBackfillRunner}).
 */
@Service
public class VersionChangeSummaryBackfillService {

    public static final String BACKFILL_NAME = "version-change-summary-v1";

    private static final Logger log = LoggerFactory.getLogger(VersionChangeSummaryBackfillService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public VersionChangeSummaryBackfillService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
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
                SELECT id, current_version_no, current_change_summary
                  FROM documents
                 WHERE deleted_at IS NULL
                """);

        int documentsTouched = 0;
        int versionsShifted = 0;

        for (Map<String, Object> doc : docs) {
            UUID docId = (UUID) doc.get("id");
            List<Map<String, Object>> versions = jdbc.queryForList("""
                    SELECT version_no, change_summary
                      FROM document_versions
                     WHERE document_id = ?
                     ORDER BY version_no ASC
                    """, docId);
            if (versions.isEmpty()) {
                continue;
            }

            Map<Integer, String> oldSummaries = new HashMap<>();
            for (Map<String, Object> v : versions) {
                int no = ((Number) v.get("version_no")).intValue();
                Object raw = v.get("change_summary");
                oldSummaries.put(no, raw == null ? null : String.valueOf(raw));
            }

            ShiftResult shifted = shiftSummaries(oldSummaries);
            for (Map.Entry<Integer, String> e : shifted.archived().entrySet()) {
                jdbc.update("""
                        UPDATE document_versions
                           SET change_summary = ?
                         WHERE document_id = ? AND version_no = ?
                        """,
                        e.getValue(), docId, e.getKey());
                versionsShifted++;
            }

            jdbc.update("""
                    UPDATE documents
                       SET current_change_summary = ?
                     WHERE id = ?
                    """,
                    shifted.currentSummary(), docId);
            documentsTouched++;
        }

        BackfillReport report = new BackfillReport(false, docs.size(), documentsTouched, versionsShifted);
        persist(report);
        log.info("Backfill {} : scanned={} touched={} versionsShifted={}",
                BACKFILL_NAME, report.documentsScanned(), report.documentsTouched(),
                report.versionsShifted());
        return report;
    }

    /**
     * Décale les résumés d'un cran : v1 → null ; vN (N&gt;1) ← ancien résumé de vN−1 ;
     * courant ← ancien résumé de la dernière archive.
     */
    static ShiftResult shiftSummaries(Map<Integer, String> oldSummaries) {
        if (oldSummaries == null || oldSummaries.isEmpty()) {
            return new ShiftResult(Map.of(), null);
        }
        List<Integer> nos = oldSummaries.keySet().stream().sorted().toList();
        int maxArchived = nos.getLast();
        String currentSummary = oldSummaries.get(maxArchived);
        Map<Integer, String> archived = new HashMap<>();
        for (int no : nos) {
            archived.put(no, no == 1 ? null : oldSummaries.get(no - 1));
        }
        return new ShiftResult(java.util.Collections.unmodifiableMap(archived), currentSummary);
    }

    record ShiftResult(Map<Integer, String> archived, String currentSummary) {}

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

    public record BackfillReport(
            boolean skipped,
            int documentsScanned,
            int documentsTouched,
            int versionsShifted
    ) {
        static BackfillReport skippedReport() {
            return new BackfillReport(true, 0, 0, 0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", BACKFILL_NAME);
            m.put("skipped", skipped);
            m.put("documentsScanned", documentsScanned);
            m.put("documentsTouched", documentsTouched);
            m.put("versionsShifted", versionsShifted);
            return m;
        }
    }
}
