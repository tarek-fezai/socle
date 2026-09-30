package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.space.ExternalReferenceNotify;
import eu.socle.storage.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Index {@code document_links} — recalculé à chaque écriture de contenu TipTap.
 * Soft-delete : les lignes sont conservées ; filtrage via {@code documents.deleted_at}.
 */
@Service
public class DocumentLinkService {

    public static final String LINK_TYPE_TRANSCLUSION = "transclusion";
    public static final String BACKFILL_NAME = "document-links-v1";

    private static final Logger log = LoggerFactory.getLogger(DocumentLinkService.class);

    private final JdbcTemplate jdbc;
    private final TransclusionResolver transclusionResolver;
    private final DocumentStore documentStore;
    private final DocumentRepository documentRepository;
    private final ExternalReferenceNotify externalReferenceNotify;
    private final ObjectMapper objectMapper;

    public DocumentLinkService(
            JdbcTemplate jdbc,
            TransclusionResolver transclusionResolver,
            DocumentStore documentStore,
            DocumentRepository documentRepository,
            ExternalReferenceNotify externalReferenceNotify,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.transclusionResolver = transclusionResolver;
        this.documentStore = documentStore;
        this.documentRepository = documentRepository;
        this.externalReferenceNotify = externalReferenceNotify;
        this.objectMapper = objectMapper;
    }

    /**
     * Remplace les arêtes sortantes du document à partir du JSON TipTap écrit
     * (pas du blob Git). Même transaction que create / update / restore.
     */
    @Transactional
    public void replaceOutgoingLinks(UUID sourceId, UUID sourceSpaceId, Map<String, Object> tipTapBody) {
        if (sourceId == null || sourceSpaceId == null) {
            return;
        }
        List<UUID> targets = tipTapBody == null
                ? List.of()
                : transclusionResolver.extractDirectTargets(tipTapBody);

        jdbc.update(
                "DELETE FROM document_links WHERE source_id = ? AND link_type = ?",
                sourceId, LINK_TYPE_TRANSCLUSION);

        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID targetId : targets) {
            if (targetId == null || !seen.add(targetId)) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO document_links (source_id, target_id, source_space_id, link_type)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    sourceId, targetId, sourceSpaceId, LINK_TYPE_TRANSCLUSION);

            UUID targetSpaceId = lookupSpaceId(targetId);
            if (targetSpaceId != null && !sourceSpaceId.equals(targetSpaceId)) {
                externalReferenceNotify.notifyOwnersOnFirstPair(sourceSpaceId, targetSpaceId);
            }
        }
    }

    /** Déplacement d'espace : met à jour {@code source_space_id} sans recalculer les cibles. */
    @Transactional
    public void updateSourceSpace(UUID sourceId, UUID newSpaceId) {
        if (sourceId == null || newSpaceId == null) {
            return;
        }
        jdbc.update(
                "UPDATE document_links SET source_space_id = ? WHERE source_id = ?",
                newSpaceId, sourceId);
    }

    public List<LinkRow> outgoingFromSpace(UUID spaceId) {
        return jdbc.query("""
                SELECT dl.source_id, dl.target_id, dl.source_space_id
                  FROM document_links dl
                  JOIN documents s ON s.id = dl.source_id AND s.deleted_at IS NULL
                 WHERE dl.source_space_id = ?
                   AND dl.link_type = ?
                """,
                (rs, i) -> new LinkRow(
                        (UUID) rs.getObject("source_id"),
                        (UUID) rs.getObject("target_id"),
                        (UUID) rs.getObject("source_space_id")),
                spaceId, LINK_TYPE_TRANSCLUSION);
    }

    public List<LinkRow> incomingToSpace(UUID spaceId) {
        return jdbc.query("""
                SELECT dl.source_id, dl.target_id, dl.source_space_id
                  FROM document_links dl
                  JOIN documents s ON s.id = dl.source_id AND s.deleted_at IS NULL
                  JOIN documents t ON t.id = dl.target_id AND t.deleted_at IS NULL
                 WHERE t.space_id = ?
                   AND dl.source_space_id <> ?
                   AND dl.link_type = ?
                """,
                (rs, i) -> new LinkRow(
                        (UUID) rs.getObject("source_id"),
                        (UUID) rs.getObject("target_id"),
                        (UUID) rs.getObject("source_space_id")),
                spaceId, spaceId, LINK_TYPE_TRANSCLUSION);
    }

    public Set<UUID> indexedTargets(UUID sourceId) {
        List<UUID> rows = jdbc.query("""
                SELECT target_id FROM document_links
                 WHERE source_id = ? AND link_type = ?
                """,
                (rs, i) -> (UUID) rs.getObject("target_id"),
                sourceId, LINK_TYPE_TRANSCLUSION);
        return new LinkedHashSet<>(rows);
    }

    public boolean isBackfillApplied() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM authz_migrations WHERE name = ?",
                Integer.class,
                BACKFILL_NAME);
        return n != null && n > 0;
    }

    /**
     * Backfill idempotent depuis le contenu canonique ({@link DocumentStore}).
     * Journalisé dans {@code authz_migrations} ({@value BACKFILL_NAME}).
     */
    @Transactional
    public BackfillReport backfill(boolean force) {
        if (!force && isBackfillApplied()) {
            log.info("Backfill {} déjà appliqué — skip", BACKFILL_NAME);
            return BackfillReport.skippedReport();
        }
        int scanned = 0;
        int rewritten = 0;
        int errors = 0;
        for (DocumentEntity doc : documentRepository.findAllByOrderByUpdatedAtDesc()) {
            if (doc.getDeletedAt() != null) {
                continue;
            }
            scanned++;
            try {
                Map<String, Object> canonical =
                        documentStore.readCurrentContent(doc.getId(), doc.getBody());
                replaceOutgoingLinks(doc.getId(), doc.getSpaceId(), canonical);
                rewritten++;
            } catch (RuntimeException e) {
                errors++;
                log.error("Backfill document_links échoué pour {}", doc.getId(), e);
            }
        }
        BackfillReport report = new BackfillReport(false, scanned, rewritten, errors);
        persistBackfill(report);
        return report;
    }

    /** Divergence index ↔ contenu canonique (lecture seule). */
    @Transactional(readOnly = true)
    public DriftReport listDrift() {
        List<DriftItem> items = new ArrayList<>();
        int scanned = 0;
        for (DocumentEntity doc : documentRepository.findAllByOrderByUpdatedAtDesc()) {
            if (doc.getDeletedAt() != null) {
                continue;
            }
            scanned++;
            Map<String, Object> canonical =
                    documentStore.readCurrentContent(doc.getId(), doc.getBody());
            Set<UUID> expected = new LinkedHashSet<>(
                    transclusionResolver.extractDirectTargets(canonical));
            Set<UUID> indexed = indexedTargets(doc.getId());
            if (!expected.equals(indexed)) {
                Set<UUID> missing = new HashSet<>(expected);
                missing.removeAll(indexed);
                Set<UUID> extra = new HashSet<>(indexed);
                extra.removeAll(expected);
                items.add(new DriftItem(doc.getId(), doc.getTitle(),
                        List.copyOf(missing), List.copyOf(extra)));
            }
        }
        return new DriftReport(scanned, items.size(), List.copyOf(items));
    }

    private UUID lookupSpaceId(UUID documentId) {
        List<UUID> rows = jdbc.query(
                "SELECT space_id FROM documents WHERE id = ?",
                (rs, i) -> (UUID) rs.getObject("space_id"),
                documentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void persistBackfill(BackfillReport report) {
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

    public record LinkRow(UUID sourceId, UUID targetId, UUID sourceSpaceId) {}

    public record BackfillReport(boolean skipped, int documentsScanned, int rewritten, int errors) {
        static BackfillReport skippedReport() {
            return new BackfillReport(true, 0, 0, 0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skipped", skipped);
            m.put("documentsScanned", documentsScanned);
            m.put("rewritten", rewritten);
            m.put("errors", errors);
            m.put("migration", BACKFILL_NAME);
            return m;
        }
    }

    public record DriftReport(int documentsScanned, int driftCount, List<DriftItem> items) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentsScanned", documentsScanned);
            m.put("driftCount", driftCount);
            m.put("items", items);
            m.put("repairHint",
                    "POST /api/v1/admin/authz/backfill-document-links?force=true "
                            + "— recalcule document_links depuis le contenu canonique.");
            return m;
        }
    }

    public record DriftItem(
            UUID documentId,
            String title,
            List<UUID> missingInIndex,
            List<UUID> extraInIndex
    ) {}
}
