// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Liens sortants / entrants d'un document pour le rail de lecture.
 * Candidats lus dans {@code document_links} (bornés), puis filtrés par OpenFGA {@code viewer}
 * (BatchCheck borné) : un document non lisible n'apparaît jamais (ni titre ni id).
 */
@Service
public class DocumentRelatedLinksService {

    /** Plafond de candidats par direction avant BatchCheck. */
    public static final int MAX_CANDIDATES_PER_DIRECTION = 50;

    private static final String SCOPE_LABEL = "document-links";

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;

    public DocumentRelatedLinksService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
    }

    public record LinkedDocument(UUID id, String title) {}

    public record RelatedLinks(List<LinkedDocument> outgoing, List<LinkedDocument> incoming) {}

    /**
     * Documents qui lient <em>vers</em> {@code documentId} (entrants), filtrés viewer.
     * {@code hiddenCount} = candidats non visibles (jamais d'id/titre exposés pour ceux-là).
     */
    public record ImpactedIncoming(List<LinkedDocument> visible, int hiddenCount) {}

    @Transactional(readOnly = true)
    public RelatedLinks links(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireDocumentViewer(user.getId(), documentId);

        Map<UUID, String> outgoing = candidates("""
                SELECT DISTINCT t.id, t.title
                  FROM document_links dl
                  JOIN documents t ON t.id = dl.target_id AND t.deleted_at IS NULL
                 WHERE dl.source_id = ?
                 ORDER BY t.title, t.id
                 LIMIT ?
                """, documentId);
        Map<UUID, String> incoming = incomingCandidates(documentId);

        // Un seul BatchCheck sur l'union des candidats (≤ 2 × MAX_CANDIDATES_PER_DIRECTION).
        Set<UUID> all = new HashSet<>(outgoing.keySet());
        all.addAll(incoming.keySet());
        all.remove(documentId);
        Set<UUID> viewable = new HashSet<>(
                authorizationService.filterByDocumentViewer(user.getId(), all, SCOPE_LABEL));

        return new RelatedLinks(keep(outgoing, viewable), keep(incoming, viewable));
    }

    /**
     * Liens impactés pour une approbation = documents sources pointant vers le document sous revue.
     * Les sources non lisibles (BatchCheck viewer) ne figurent pas dans {@code visible} ;
     * seul leur nombre est renvoyé ({@code hiddenCount}).
     */
    @Transactional(readOnly = true)
    public ImpactedIncoming impactedIncoming(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireDocumentViewer(user.getId(), documentId);

        Map<UUID, String> incoming = incomingCandidates(documentId);
        incoming.remove(documentId);
        if (incoming.isEmpty()) {
            return new ImpactedIncoming(List.of(), 0);
        }
        Set<UUID> viewable = new HashSet<>(
                authorizationService.filterByDocumentViewer(user.getId(), incoming.keySet(), SCOPE_LABEL));
        List<LinkedDocument> visible = keep(incoming, viewable);
        int hidden = incoming.size() - visible.size();
        return new ImpactedIncoming(visible, Math.max(0, hidden));
    }

    private void requireDocumentViewer(UUID userId, UUID documentId) {
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE id = ? AND deleted_at IS NULL",
                Integer.class, documentId);
        if (exists == null || exists == 0
                || !authorizationService.hasRelation(userId, "document", documentId, "viewer")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
    }

    private Map<UUID, String> incomingCandidates(UUID documentId) {
        return candidates("""
                SELECT DISTINCT s.id, s.title
                  FROM document_links dl
                  JOIN documents s ON s.id = dl.source_id AND s.deleted_at IS NULL
                 WHERE dl.target_id = ?
                 ORDER BY s.title, s.id
                 LIMIT ?
                """, documentId);
    }

    private Map<UUID, String> candidates(String sql, UUID documentId) {
        Map<UUID, String> out = new LinkedHashMap<>();
        jdbc.query(sql,
                (rs, i) -> {
                    out.put((UUID) rs.getObject("id"), rs.getString("title"));
                    return null;
                },
                documentId, MAX_CANDIDATES_PER_DIRECTION);
        return out;
    }

    private static List<LinkedDocument> keep(Map<UUID, String> candidates, Set<UUID> viewable) {
        return candidates.entrySet().stream()
                .filter(e -> viewable.contains(e.getKey()))
                .map(e -> new LinkedDocument(e.getKey(), e.getValue()))
                .toList();
    }
}
