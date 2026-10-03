// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistance du contenu documentaire + historique de versions.
 * Un seul provider actif par instance ({@code socle.storage.provider}).
 */
public interface DocumentStore {

    /**
     * Première écriture de contenu (création document). En Git : commit initial.
     * En relational : no-op sur les versions (comme aujourd'hui — pas de row avant update).
     *
     * @return SHA HEAD Git après commit, ou {@code null} en mode relational
     */
    String createContent(UUID documentId, Map<String, Object> body, UUID authorId);

    /** Variante avec résumé initial (message de commit Git si fourni). */
    default String createContent(
            UUID documentId, Map<String, Object> body, UUID authorId, String changeSummary) {
        return createContent(documentId, body, authorId);
    }

    /**
     * Contenu courant pour GET/Edit. Git : blob HEAD (canonique) ; relational : projection Postgres.
     */
    Map<String, Object> readCurrentContent(UUID documentId, Map<String, Object> dbProjection);

    /**
     * Archive le body courant sous {@code archivedVersionNo}, le nouveau contenu devient courant.
     *
     * @param contentAuthorId auteur du <strong>contenu</strong> archivé ({@code documents.updated_by} avant mutation)
     * @param archivedBy      utilisateur qui déclenche l'archivage
     */
    void archiveVersion(
            UUID documentId,
            int archivedVersionNo,
            Map<String, Object> previousBody,
            UUID contentAuthorId,
            UUID archivedBy,
            String changeSummary
    );

    /**
     * Après archive : persiste le nouveau contenu courant (Git commit HEAD / projection).
     * Relational : no-op (le body reste sur {@code documents} via DocumentService).
     *
     * @param contentAuthorId auteur du contenu écrit (Git {@code author})
     * @param committerId     qui a déclenché l'écriture (Git {@code committer})
     * @param expectedGitHeadSha en mode Git, SHA HEAD attendu ({@code documents.git_head_sha}) ;
     *                             mismatch → 409. Ignoré en relational.
     * @return nouveau SHA HEAD Git, ou {@code null} en relational
     */
    String writeCurrentContent(
            UUID documentId,
            Map<String, Object> body,
            UUID contentAuthorId,
            UUID committerId,
            String changeSummary,
            String expectedGitHeadSha
    );

    Optional<StoredVersion> findVersion(UUID documentId, int versionNo);

    Page<StoredVersion> listVersions(UUID documentId, int page, int size);

    /**
     * Liste paginée par offset absolu (pas par numéro de page Spring).
     * Requis pour la pagination « version courante en tête » (offset décalé de 1).
     */
    Page<StoredVersion> listVersionsFromOffset(UUID documentId, int offset, int size);

    /** Nombre de versions archivées (hors courante). */
    long countVersions(UUID documentId);

    /**
     * Message de commit HEAD du document (mode git), sinon vide.
     * Sert de repli pour le résumé de la version courante avant backfill relational.
     */
    default Optional<String> currentContentChangeSummary(UUID documentId) {
        return Optional.empty();
    }

    VersionDiffResult diff(UUID documentId, int versionA, int versionB);

    /** Corps d'une version archivée (restore). */
    Map<String, Object> loadVersionBody(UUID documentId, int versionNo);

    /**
     * Instant de la dernière <strong>écriture de contenu</strong> (create / update / restore
     * via ce store). Ne reflète pas les transitions de workflow.
     *
     * @param documentCreatedAt repli si aucune version archivée (doc jamais mis à jour)
     */
    java.time.Instant lastContentModifiedAt(UUID documentId, java.time.Instant documentCreatedAt);

    record StoredVersion(
            UUID documentId,
            int versionNo,
            Map<String, Object> bodySnapshot,
            UUID authorId,
            UUID archivedBy,
            String changeSummary,
            java.time.Instant createdAt,
            String gitCommitSha
    ) {}

    record DiffChange(String path, String op, Object before, Object after) {}

    record VersionDiffResult(int fromVersion, int toVersion, List<DiffChange> changes) {}
}
