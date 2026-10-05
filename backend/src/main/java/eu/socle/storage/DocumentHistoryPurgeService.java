// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.storage.DocumentStore.HistoryPurgeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestre la purge Git d'une suppression définitive : réécriture d'historique
 * ({@link DocumentStore#purgeDocumentsHistory}), resynchronisation des SHA en base, audit
 * {@code DOCUMENT_GIT_HISTORY_PURGED}.
 *
 * <p>Dans une transaction, la purge est reportée à {@code afterCommit} : l'historique Git n'est
 * jamais réécrit si la suppression SQL est annulée. Les documents d'une même transaction sont
 * purgés en un seul passage (une réécriture + un GC).
 */
@Service
public class DocumentHistoryPurgeService {

    private static final Logger log = LoggerFactory.getLogger(DocumentHistoryPurgeService.class);

    private final JdbcTemplate jdbc;
    private final DocumentStore documentStore;
    private final AuditService auditService;
    private final TransactionTemplate requiresNew;

    @Autowired
    public DocumentHistoryPurgeService(
            JdbcTemplate jdbc,
            ObjectProvider<DocumentStore> documentStore,
            AuditService auditService,
            PlatformTransactionManager transactionManager
    ) {
        this(jdbc, documentStore.getIfAvailable(), auditService, transactionManager);
    }

    public DocumentHistoryPurgeService(
            JdbcTemplate jdbc,
            DocumentStore documentStore,
            AuditService auditService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.documentStore = documentStore;
        this.auditService = auditService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Programme la purge Git du document après le commit de la transaction courante
     * (immédiatement si aucune transaction n'est active). Ne lève jamais : un échec est journalisé
     * (la suppression SQL est déjà définitive) — voir {@code docs/git-purge.md}, « reprise ».
     */
    public void purgeAfterCommit(UUID actorId, UUID documentId) {
        if (documentStore == null || documentId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runSafely(actorId, Set.of(documentId));
            return;
        }
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(this);
        if (pending == null) {
            Pending created = new Pending(actorId);
            TransactionSynchronizationManager.bindResource(this, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runSafely(created.actorId, created.documentIds);
                }

                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(DocumentHistoryPurgeService.this)) {
                        TransactionSynchronizationManager.unbindResource(DocumentHistoryPurgeService.this);
                    }
                }
            });
            pending = created;
        }
        pending.documentIds.add(documentId);
    }

    /**
     * Purge synchrone (tests, reprise opérateur).
     *
     * @param actorId {@code null} = acteur système
     */
    public Optional<HistoryPurgeResult> purgeNow(UUID actorId, Collection<UUID> documentIds) {
        if (documentStore == null || documentIds == null || documentIds.isEmpty()) {
            return Optional.empty();
        }
        Optional<HistoryPurgeResult> result = documentStore.purgeDocumentsHistory(documentIds);
        if (result.isEmpty()) {
            return result;
        }
        HistoryPurgeResult r = result.get();
        resyncShas(r.commitMapping());
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

    /** Remappe {@code document_versions.git_commit_sha} et {@code documents.git_head_sha}. */
    void resyncShas(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(mapping.size());
        mapping.forEach((oldSha, newSha) -> args.add(new Object[] {newSha, oldSha}));
        requiresNew.executeWithoutResult(status -> {
            jdbc.batchUpdate(
                    "UPDATE document_versions SET git_commit_sha = ? WHERE git_commit_sha = ?", args);
            jdbc.batchUpdate(
                    "UPDATE documents SET git_head_sha = ? WHERE git_head_sha = ?", args);
        });
    }

    private void runSafely(UUID actorId, Set<UUID> documentIds) {
        try {
            purgeNow(actorId, documentIds);
        } catch (RuntimeException e) {
            log.error("Purge de l'historique Git en échec pour {} document(s) {} — la suppression SQL est "
                    + "définitive ; relancer la purge (docs/git-purge.md)", documentIds.size(), documentIds, e);
        }
    }

    private static final class Pending {
        final UUID actorId;
        final Set<UUID> documentIds = new LinkedHashSet<>();

        Pending(UUID actorId) {
            this.actorId = actorId;
        }
    }
}
