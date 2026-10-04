// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Séparation des tâches (quatre yeux) : le décideur est exclu s'il est le demandeur
 * ou un contributeur de contenu depuis la dernière version approuvée.
 */
public final class FourEyesPolicy {

    private FourEyesPolicy() {}

    /**
     * Contributeurs = auteurs de contenu distincts des versions avec
     * {@code version_no > dernière submitted_version_no approuvée}, plus
     * {@code documents.updated_by}. Sans version approuvée : toutes les versions.
     *
     * <p>Schéma actuel : {@code submitted_version_no} = n° du contenu approuvé (pas de
     * version « Soumission » dupliquée). Ancien schéma : {@code submitted_version_no} =
     * contenu archivé avant le bump vide — le seuil {@code > submitted} reste correct
     * (la version vide N+1 n'est pas encore archivée tant qu'aucune édition n'a lieu).
     */
    public static Set<UUID> loadContentContributors(JdbcTemplate jdbc, UUID documentId) {
        Integer lastApproved = jdbc.query("""
                SELECT ar.submitted_version_no
                  FROM approval_requests ar
                 WHERE ar.document_id = ?
                   AND ar.status = 'approuve'
                   AND ar.submitted_version_no IS NOT NULL
                 ORDER BY ar.resolved_at DESC NULLS LAST, ar.created_at DESC
                 LIMIT 1
                """,
                rs -> rs.next() ? (Integer) rs.getObject(1) : null,
                documentId);
        int afterVersion = lastApproved != null ? lastApproved : 0;

        List<UUID> authors = jdbc.query("""
                SELECT DISTINCT dv.author_id
                  FROM document_versions dv
                 WHERE dv.document_id = ?
                   AND dv.version_no > ?
                   AND dv.author_id IS NOT NULL
                """,
                (rs, i) -> (UUID) rs.getObject("author_id"),
                documentId,
                afterVersion);

        Set<UUID> contributors = new LinkedHashSet<>();
        if (authors != null) {
            contributors.addAll(authors);
        }

        List<UUID> updatedBy = jdbc.query("""
                SELECT COALESCE(updated_by, created_by)
                  FROM documents
                 WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> (UUID) rs.getObject(1),
                documentId);
        if (updatedBy != null && !updatedBy.isEmpty() && updatedBy.getFirst() != null) {
            contributors.add(updatedBy.getFirst());
        }
        return contributors;
    }

    public static boolean isConflict(UUID actorId, UUID requestedBy, Set<UUID> contributors) {
        if (actorId == null) {
            return false;
        }
        if (actorId.equals(requestedBy)) {
            return true;
        }
        return contributors != null && contributors.contains(actorId);
    }
}
