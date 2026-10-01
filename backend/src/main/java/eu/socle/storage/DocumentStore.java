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

    /**
     * Contenu courant pour GET/Edit. Git : blob HEAD (canonique) ; relational : projection Postgres.
     */
    Map<String, Object> readCurrentContent(UUID documentId, Map<String, Object> dbProjection);

    /**
     * Archive le body courant sous {@code archivedVersionNo}, le nouveau contenu devient courant.
     */
    void archiveVersion(
            UUID documentId,
            int archivedVersionNo,
            Map<String, Object> previousBody,
            UUID authorId,
            String changeSummary
    );

    /**
     * Après archive : persiste le nouveau contenu courant (Git commit HEAD / projection).
     * Relational : no-op (le body reste sur {@code documents} via DocumentService).
     *
     * @param expectedGitHeadSha en mode Git, SHA HEAD attendu ({@code documents.git_head_sha}) ;
     *                             mismatch → 409. Ignoré en relational.
     * @return nouveau SHA HEAD Git, ou {@code null} en relational
     */
    String writeCurrentContent(
            UUID documentId,
            Map<String, Object> body,
            UUID authorId,
            String changeSummary,
            String expectedGitHeadSha
    );

    Optional<StoredVersion> findVersion(UUID documentId, int versionNo);

    Page<StoredVersion> listVersions(UUID documentId, int page, int size);

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
            String changeSummary,
            java.time.Instant createdAt,
            String gitCommitSha
    ) {}

    record DiffChange(String path, String op, Object before, Object after) {}

    record VersionDiffResult(int fromVersion, int toVersion, List<DiffChange> changes) {}
}
