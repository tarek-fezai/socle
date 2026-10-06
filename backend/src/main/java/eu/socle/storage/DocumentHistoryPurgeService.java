// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.storage.DocumentStore.HistoryPurgeResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Purge Git d'une suppression définitive : réécriture d'historique, remappage SHA
 * ({@link GitShaRemappingService}), audit {@code DOCUMENT_GIT_HISTORY_PURGED}.
 *
 * <p>L'inscription durable se fait via {@link GitPurgeQueueService} (même transaction SQL).
 */
@Service
public class DocumentHistoryPurgeService {

    private final DocumentStore documentStore;
    private final AuditService auditService;
    private final GitShaRemappingService shaRemapping;
    private final ObjectProvider<GitPurgeQueueService> gitPurgeQueue;

    @Autowired
    public DocumentHistoryPurgeService(
            ObjectProvider<DocumentStore> documentStore,
            AuditService auditService,
            GitShaRemappingService shaRemapping,
            ObjectProvider<GitPurgeQueueService> gitPurgeQueue
    ) {
        this(documentStore.getIfAvailable(), auditService, shaRemapping, gitPurgeQueue);
    }

    /** Constructeur tests (sans file durable). */
    public DocumentHistoryPurgeService(
            DocumentStore documentStore,
            AuditService auditService,
            GitShaRemappingService shaRemapping
    ) {
        this(documentStore, auditService, shaRemapping, null);
    }

    DocumentHistoryPurgeService(
            DocumentStore documentStore,
            AuditService auditService,
            GitShaRemappingService shaRemapping,
            ObjectProvider<GitPurgeQueueService> gitPurgeQueue
    ) {
        this.documentStore = documentStore;
        this.auditService = auditService;
        this.shaRemapping = shaRemapping;
        this.gitPurgeQueue = gitPurgeQueue;
    }

    /** Inscrit une purge Git (corbeille par défaut) dans la transaction courante. */
    public void purgeAfterCommit(UUID actorId, UUID documentId) {
        purgeAfterCommit(actorId, documentId, GitPurgeMotif.CORBEILLE);
    }

    public void purgeAfterCommit(UUID actorId, UUID documentId, GitPurgeMotif motif) {
        if (documentId == null || motif == null) {
            return;
        }
        if (gitPurgeQueue != null) {
            GitPurgeQueueService queue = gitPurgeQueue.getIfAvailable();
            if (queue != null) {
                queue.enqueueInCurrentTransaction(actorId, documentId, motif);
            }
        }
    }

    /**
     * Purge synchrone (file durable, tests).
     *
     * @param actorId {@code null} = acteur système
     */
    public Optional<HistoryPurgeResult> purgeNow(UUID actorId, Collection<UUID> documentIds) {
        if (documentStore == null || documentIds == null || documentIds.isEmpty()) {
            return Optional.empty();
        }
        Optional<HistoryPurgeResult> result = documentStore.purgeDocumentsHistory(
                documentIds, shaRemapping::remap);
        if (result.isEmpty()) {
            return result;
        }
        HistoryPurgeResult r = result.get();
        for (UUID id : r.documentIds()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("documentId", id.toString());
            meta.put("commitsRewritten", r.commitsRewritten());
            meta.put("commitsDropped", r.commitsDropped());
            meta.put("oldHead", r.oldHead());
            meta.put("newHead", r.newHead());
            meta.put("batchSize", r.documentIds().size());
            auditService.record(actorId, actorId == null, AuditActions.DOCUMENT_GIT_HISTORY_PURGED,
                    "document", id, meta, null);
        }
        return result;
    }
}
