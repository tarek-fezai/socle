// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Détection lecture seule : dérive entre projection Postgres ({@code documents.body})
 * et blob Git HEAD.
 *
 * <p>Compare le contenu complet (pas seulement les transclusions) : la projection
 * re-sérialisée ({@code fromMarkdown(toMarkdown(body))}) doit égaler le blob lu.
 *
 * <p>Ne corrige rien. Réparation : commit append-only depuis la projection.
 * Voir {@code docs/storage-providers.md}.
 */
@Service
public class GitProjectionDriftService {

    private final DocumentRepository documentRepository;
    private final DocumentStore documentStore;
    private final StorageProperties storageProperties;

    public GitProjectionDriftService(
            DocumentRepository documentRepository,
            DocumentStore documentStore,
            StorageProperties storageProperties
    ) {
        this.documentRepository = documentRepository;
        this.documentStore = documentStore;
        this.storageProperties = storageProperties;
    }

    public DriftReport listMissingTransclusionsInGitHead() {
        return listContentDrift();
    }

    /**
     * @return liste vide si provider ≠ git
     */
    public DriftReport listContentDrift() {
        if (!storageProperties.isGit()) {
            return new DriftReport(
                    "relational",
                    List.of(),
                    "Provider relational — pas de blob Git à comparer.");
        }

        List<DriftItem> items = new ArrayList<>();
        for (DocumentEntity doc : documentRepository.findAllByOrderByUpdatedAtDesc()) {
            if (doc.getDeletedAt() != null) {
                continue;
            }
            Map<String, Object> projection = doc.getBody();
            if (projection == null || projection.isEmpty()) {
                continue;
            }
            Map<String, Object> projectedCanonical =
                    TipTapMarkdown.fromMarkdown(TipTapMarkdown.toMarkdown(projection));
            Map<String, Object> fromGit = documentStore.readCurrentContent(doc.getId(), Map.of());
            if (!TipTapMarkdown.deepEquals(projectedCanonical, fromGit)) {
                items.add(new DriftItem(
                        doc.getId(),
                        doc.getTitle(),
                        List.copyOf(GitProjectionDriftService.collectTransclusionIds(projection)),
                        "Contenu projection ≠ blob Git HEAD. "
                                + "Réparer : writeCurrentContent(body projection, expectedHeadSha=git_head_sha). "
                                + "Commit append-only — ne pas réécrire l'historique."
                ));
            }
        }
        return new DriftReport("git", items, REPAIR_HINT);
    }

    static final String REPAIR_HINT =
            "Procédure de réparation (manuelle) : pour chaque document listé, "
                    + "relire documents.body (projection), appeler DocumentStore.writeCurrentContent "
                    + "avec expectedHeadSha = documents.git_head_sha pour produire un nouveau commit "
                    + "append-only. Ne jamais force-push ni amend de l'historique Git.";

    static java.util.Set<UUID> collectTransclusionIds(Map<String, Object> body) {
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        if (body == null) {
            return ids;
        }
        walk(body, ids);
        return ids;
    }

    private static void walk(Object node, java.util.Set<UUID> ids) {
        if (!(node instanceof Map<?, ?> m)) {
            if (node instanceof List<?> list) {
                for (Object e : list) {
                    walk(e, ids);
                }
            }
            return;
        }
        if ("transclusion".equals(String.valueOf(m.get("type")))) {
            Object attrs = m.get("attrs");
            if (attrs instanceof Map<?, ?> am && am.get("documentId") != null) {
                try {
                    ids.add(UUID.fromString(String.valueOf(am.get("documentId"))));
                } catch (IllegalArgumentException ignored) {
                    // ignore
                }
            }
        }
        Object content = m.get("content");
        if (content instanceof List<?> list) {
            for (Object e : list) {
                walk(e, ids);
            }
        }
    }

    public record DriftItem(
            UUID documentId,
            String title,
            List<UUID> missingTransclusionTargetIds,
            String repairHint
    ) {}

    public record DriftReport(
            String storageProvider,
            List<DriftItem> items,
            String procedure
    ) {}
}
