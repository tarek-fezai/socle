// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.BodyDiff;
import eu.socle.document.DocumentVersionEntity;
import eu.socle.document.DocumentVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider Postgres/JSONB — comportement historique inchangé.
 * Contenu courant : table {@code documents} (géré par DocumentService).
 * Historique : {@code document_versions}.
 */
public class RelationalDocumentStore implements DocumentStore {

    private final DocumentVersionRepository versionRepository;

    public RelationalDocumentStore(DocumentVersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    @Override
    public String createContent(UUID documentId, Map<String, Object> body, UUID authorId) {
        // Pas de row versions à la création — aligné sur le comportement existant.
        return null;
    }

    @Override
    public Map<String, Object> readCurrentContent(UUID documentId, Map<String, Object> dbProjection) {
        if (dbProjection == null) {
            return Map.of();
        }
        return new java.util.HashMap<>(dbProjection);
    }

    @Override
    public void archiveVersion(
            UUID documentId,
            int archivedVersionNo,
            Map<String, Object> previousBody,
            UUID authorId,
            String changeSummary
    ) {
        DocumentVersionEntity version = new DocumentVersionEntity();
        version.setDocumentId(documentId);
        version.setVersionNo(archivedVersionNo);
        version.setBodySnapshot(previousBody);
        version.setAuthorId(authorId);
        version.setChangeSummary(changeSummary);
        versionRepository.save(version);
    }

    @Override
    public String writeCurrentContent(
            UUID documentId,
            Map<String, Object> body,
            UUID authorId,
            String changeSummary,
            String expectedGitHeadSha
    ) {
        // Contenu courant = colonne documents.body (DocumentService).
        return null;
    }

    @Override
    public Optional<StoredVersion> findVersion(UUID documentId, int versionNo) {
        return versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .map(this::toStored);
    }

    @Override
    public Page<StoredVersion> listVersions(UUID documentId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<DocumentVersionEntity> result =
                versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId, pageable);
        if (result == null) {
            return Page.empty(pageable);
        }
        return result.map(this::toStored);
    }

    @Override
    public java.time.Instant lastContentModifiedAt(UUID documentId, java.time.Instant documentCreatedAt) {
        PageRequest pageable = PageRequest.of(0, 1);
        Page<DocumentVersionEntity> latest =
                versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId, pageable);
        if (latest != null && !latest.isEmpty()) {
            java.time.Instant at = latest.getContent().getFirst().getCreatedAt();
            if (at != null) {
                return at;
            }
        }
        return documentCreatedAt != null ? documentCreatedAt : java.time.Instant.EPOCH;
    }

    @Override
    public VersionDiffResult diff(UUID documentId, int versionA, int versionB) {
        DocumentVersionEntity from = versionRepository.findByDocumentIdAndVersionNo(documentId, versionA)
                .orElseThrow(() -> notFound(versionA));
        DocumentVersionEntity to = versionRepository.findByDocumentIdAndVersionNo(documentId, versionB)
                .orElseThrow(() -> notFound(versionB));
        BodyDiff.DiffResult result = BodyDiff.diff(
                versionA, versionB, from.getBodySnapshot(), to.getBodySnapshot());
        List<DiffChange> changes = result.changes().stream()
                .map(c -> new DiffChange(c.path(), c.op(), c.before(), c.after()))
                .toList();
        return new VersionDiffResult(versionA, versionB, changes);
    }

    @Override
    public Map<String, Object> loadVersionBody(UUID documentId, int versionNo) {
        return findVersion(documentId, versionNo)
                .map(StoredVersion::bodySnapshot)
                .orElseThrow(() -> notFound(versionNo));
    }

    private StoredVersion toStored(DocumentVersionEntity v) {
        return new StoredVersion(
                v.getDocumentId(),
                v.getVersionNo(),
                v.getBodySnapshot(),
                v.getAuthorId(),
                v.getChangeSummary(),
                v.getCreatedAt(),
                v.getGitCommitSha()
        );
    }

    private static ResponseStatusException notFound(int versionNo) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Version " + versionNo + " introuvable pour ce document");
    }
}
