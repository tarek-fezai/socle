// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * File durable {@code git_purge_queue} : inscription dans la transaction SQL de suppression,
 * traitement Git après commit (scheduler + reprise au démarrage).
 */
@Service
public class GitPurgeQueueService {

    private static final Logger log = LoggerFactory.getLogger(GitPurgeQueueService.class);

    static final Duration[] BACKOFF = {
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1),
            Duration.ofHours(6)
    };

    private final JdbcTemplate jdbc;
    private final DocumentHistoryPurgeService historyPurgeService;
    private final AuditService auditService;

    public GitPurgeQueueService(
            JdbcTemplate jdbc,
            @Lazy DocumentHistoryPurgeService historyPurgeService,
            AuditService auditService
    ) {
        this.jdbc = jdbc;
        this.historyPurgeService = historyPurgeService;
        this.auditService = auditService;
    }

    /**
     * Inscrit le document dans la file (même transaction que la suppression SQL).
     * Déclenche le traitement après commit ; immédiat si aucune transaction active.
     */
    public void enqueueInCurrentTransaction(UUID actorId, UUID documentId, GitPurgeMotif motif) {
        if (documentId == null || motif == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID entryId = insertQueueRow(actorId, Set.of(documentId), motif);
            processEntry(entryId);
            return;
        }
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(this);
        if (pending == null) {
            Pending created = new Pending(actorId, motif);
            TransactionSynchronizationManager.bindResource(this, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    flushPending(created);
                }

                @Override
                public void afterCommit() {
                    for (UUID entryId : created.committedEntryIds) {
                        processEntryAsync(entryId);
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(GitPurgeQueueService.this)) {
                        TransactionSynchronizationManager.unbindResource(GitPurgeQueueService.this);
                    }
                }
            });
            pending = created;
        } else if (pending.motif != motif || !objectsEqual(pending.actorId, actorId)) {
            flushPending(pending);
            pending = new Pending(actorId, motif);
            TransactionSynchronizationManager.bindResource(this, pending);
        }
        pending.documentIds.add(documentId);
    }

    /** Reprise au démarrage : {@code running} → {@code pending}. */
    public int recoverInterrupted() {
        return jdbc.update("""
                UPDATE git_purge_queue
                   SET status = 'pending', started_at = NULL, updated_at = now()
                 WHERE status = 'running'
                """);
    }

    /** Traite les entrées éligibles (scheduler). */
    public int processDueEntries(int limit) {
        List<UUID> ids = jdbc.query("""
                SELECT id FROM git_purge_queue
                 WHERE status IN ('pending', 'failed')
                   AND next_attempt_at <= now()
                 ORDER BY created_at
                 LIMIT ?
                """,
                (rs, i) -> (UUID) rs.getObject("id"), limit);
        int processed = 0;
        for (UUID id : ids) {
            if (processEntry(id)) {
                processed++;
            }
        }
        return processed;
    }

    /** Compteurs admin (rétention). */
    public GitPurgeQueueStats stats() {
        Long pending = jdbc.queryForObject(
                "SELECT count(*) FROM git_purge_queue WHERE status = 'pending'", Long.class);
        Long failed = jdbc.queryForObject(
                "SELECT count(*) FROM git_purge_queue WHERE status = 'failed'", Long.class);
        String lastError = jdbc.query("""
                SELECT last_error FROM git_purge_queue
                 WHERE status = 'failed' AND last_error IS NOT NULL
                 ORDER BY updated_at DESC LIMIT 1
                """,
                rs -> rs.next() ? rs.getString(1) : null);
        return new GitPurgeQueueStats(
                pending == null ? 0 : pending,
                failed == null ? 0 : failed,
                lastError);
    }

    public record GitPurgeQueueStats(long pendingCount, long failedCount, String lastError) {}

    boolean processEntry(UUID entryId) {
        if (entryId == null) {
            return false;
        }
        int claimed = jdbc.update("""
                UPDATE git_purge_queue
                   SET status = 'running', started_at = now(), updated_at = now(),
                       attempts = attempts + 1
                 WHERE id = ? AND status IN ('pending', 'failed')
                """,
                entryId);
        if (claimed == 0) {
            return false;
        }
        QueueRow row = loadRow(entryId);
        if (row == null) {
            return false;
        }
        auditAttempt(row);
        try {
            historyPurgeService.purgeNow(row.requestedBy(), row.documentIds());
            jdbc.update("""
                    UPDATE git_purge_queue
                       SET status = 'done', completed_at = now(), updated_at = now(), last_error = NULL
                     WHERE id = ?
                    """,
                    entryId);
            return true;
        } catch (RuntimeException e) {
            String message = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            Instant next = Instant.now().plus(backoffFor(row.attempts()));
            jdbc.update("""
                    UPDATE git_purge_queue
                       SET status = 'failed', last_error = ?, next_attempt_at = ?, updated_at = now()
                     WHERE id = ?
                    """,
                    message, Timestamp.from(next), entryId);
            log.error("Purge Git file {} en échec (tentative {}) : {}", entryId, row.attempts(), message, e);
            return false;
        }
    }

    void processEntryAsync(UUID entryId) {
        try {
            processEntry(entryId);
        } catch (RuntimeException e) {
            log.error("Traitement async git_purge_queue {} en échec", entryId, e);
        }
    }

    private void flushPending(Pending pending) {
        if (pending.documentIds.isEmpty()) {
            return;
        }
        UUID entryId = insertQueueRow(pending.actorId, pending.documentIds, pending.motif);
        pending.committedEntryIds.add(entryId);
        pending.documentIds.clear();
    }

    private UUID insertQueueRow(UUID actorId, Set<UUID> documentIds, GitPurgeMotif motif) {
        UUID id = UUID.randomUUID();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO git_purge_queue (id, document_ids, requested_by, motif, status)
                    VALUES (?, ?, ?, ?, 'pending')
                    """);
            ps.setObject(1, id);
            ps.setArray(2, uuidArray(connection, documentIds));
            ps.setObject(3, actorId);
            ps.setString(4, motif.dbValue());
            return ps;
        });
        return id;
    }

    private static Array uuidArray(Connection connection, Set<UUID> ids) throws SQLException {
        UUID[] arr = ids.toArray(UUID[]::new);
        return connection.createArrayOf("uuid", arr);
    }

    private QueueRow loadRow(UUID id) {
        List<QueueRow> rows = jdbc.query("""
                SELECT document_ids, requested_by, attempts
                  FROM git_purge_queue WHERE id = ?
                """,
                (rs, i) -> {
                    Array arr = rs.getArray("document_ids");
                    UUID[] uuids = arr == null ? new UUID[0] : (UUID[]) arr.getArray();
                    Set<UUID> docIds = new LinkedHashSet<>(List.of(uuids));
                    return new QueueRow(
                            docIds,
                            (UUID) rs.getObject("requested_by"),
                            rs.getInt("attempts"));
                },
                id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void auditAttempt(QueueRow row) {
        for (UUID docId : row.documentIds()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("documentId", docId.toString());
            meta.put("attempt", row.attempts());
            auditService.record(row.requestedBy(), row.requestedBy() == null, AuditActions.DOCUMENT_GIT_PURGE_ATTEMPT,
                    "document", docId, meta, null);
        }
    }

    private static Duration backoffFor(int attempts) {
        int idx = Math.min(Math.max(attempts - 1, 0), BACKOFF.length - 1);
        return BACKOFF[idx];
    }

    private static String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    private static boolean objectsEqual(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private record QueueRow(Set<UUID> documentIds, UUID requestedBy, int attempts) {}

    private static final class Pending {
        final UUID actorId;
        final GitPurgeMotif motif;
        final Set<UUID> documentIds = new LinkedHashSet<>();
        final List<UUID> committedEntryIds = new ArrayList<>();

        Pending(UUID actorId, GitPurgeMotif motif) {
            this.actorId = actorId;
            this.motif = motif;
        }
    }
}
