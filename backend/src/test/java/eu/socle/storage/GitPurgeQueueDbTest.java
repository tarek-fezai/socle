// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.storage;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.storage.DocumentStore.HistoryPurgeResult;
import eu.socle.testsupport.MigratedPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** File durable git_purge_queue : inscription transactionnelle, retentatives, reprise. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("eu.socle.testsupport.MigratedPostgres#dockerAvailable")
class GitPurgeQueueDbTest {

    @Container
    static PostgreSQLContainer<?> postgres = MigratedPostgres.newContainer();

    static JdbcTemplate jdbc;
    static final UUID SPACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID PURGED = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    AuditService audit;
    FlakyStore store;
    DocumentHistoryPurgeService purgeService;
    GitPurgeQueueService queueService;
    TransactionTemplate tx;

    @BeforeAll
    static void migrate() {
        jdbc = MigratedPostgres.migrate(postgres);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM git_purge_queue");
        audit = mock(AuditService.class);
        store = new FlakyStore();
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(jdbc.getDataSource());
        GitShaRemappingService shaRemap = new GitShaRemappingService(jdbc, txManager);
        purgeService = new DocumentHistoryPurgeService(store, audit, shaRemap);
        queueService = new GitPurgeQueueService(jdbc, purgeService, audit);
        tx = new TransactionTemplate(txManager);
    }

    @Test
    void simulatedFailureThenRetry_marksDone() {
        store.result = new HistoryPurgeResult(Set.of(PURGED), 2, 0, "old", "new", Map.of("old", "new"), null);
        store.failUntilAttempt = 1;

        tx.executeWithoutResult(s -> queueService.enqueueInCurrentTransaction(null, PURGED, GitPurgeMotif.CORBEILLE));
        assertThat(store.calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM git_purge_queue LIMIT 1", String.class))
                .isEqualTo("failed");

        jdbc.update("UPDATE git_purge_queue SET next_attempt_at = now() - interval '1 minute'");
        assertThat(queueService.processDueEntries(5)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM git_purge_queue LIMIT 1", String.class))
                .isEqualTo("done");
        assertThat(store.calls.get()).isEqualTo(2);
        verify(audit, atLeastOnce()).record(
                eq(null), eq(true), eq(AuditActions.DOCUMENT_GIT_PURGE_ATTEMPT),
                eq("document"), eq(PURGED), org.mockito.ArgumentMatchers.anyMap(), eq(null));
    }

    @Test
    void queuePersistsUntilProcessed_afterSqlCommit() {
        store.result = new HistoryPurgeResult(Set.of(PURGED), 1, 0, "a", "b", Map.of(), null);
        tx.executeWithoutResult(status ->
                queueService.enqueueInCurrentTransaction(null, PURGED, GitPurgeMotif.RETENTION));
        assertThat(jdbc.queryForObject("SELECT status FROM git_purge_queue", String.class)).isEqualTo("done");
        assertThat(store.calls.get()).isEqualTo(1);
    }

    @Test
    void recoverRunning_reprocesses() {
        store.result = new HistoryPurgeResult(Set.of(PURGED), 1, 0, "a", "b", Map.of(), null);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO git_purge_queue (id, document_ids, motif, status, attempts, next_attempt_at)
                VALUES (?, ARRAY[?]::uuid[], 'corbeille', 'running', 0, now())
                """, id, PURGED);
        assertThat(queueService.recoverInterrupted()).isEqualTo(1);
        queueService.processDueEntries(5);
        assertThat(jdbc.queryForObject("SELECT status FROM git_purge_queue WHERE id = ?", String.class, id))
                .isEqualTo("done");
    }

    static final class FlakyStore implements DocumentStore {
        HistoryPurgeResult result;
        int failUntilAttempt;
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<HistoryPurgeResult> purgeDocumentsHistory(Collection<UUID> documentIds) {
            return purgeDocumentsHistory(documentIds, null);
        }

        @Override
        public Optional<HistoryPurgeResult> purgeDocumentsHistory(
                Collection<UUID> documentIds,
                Consumer<Map<String, String>> remapBeforeWriteLockReleased
        ) {
            int attempt = calls.incrementAndGet();
            if (failUntilAttempt > 0 && attempt <= failUntilAttempt) {
                throw new IllegalStateException("boom");
            }
            if (result != null && remapBeforeWriteLockReleased != null) {
                remapBeforeWriteLockReleased.accept(result.commitMapping());
            }
            return Optional.ofNullable(result);
        }

        @Override public String createContent(UUID d, Map<String, Object> b, UUID a) { throw new UnsupportedOperationException(); }
        @Override public Map<String, Object> readCurrentContent(UUID d, Map<String, Object> p) { throw new UnsupportedOperationException(); }
        @Override public void archiveVersion(UUID d, int n, Map<String, Object> b, UUID c, UUID a, String s) { throw new UnsupportedOperationException(); }
        @Override public String writeCurrentContent(UUID d, Map<String, Object> b, UUID c, UUID m, String s, String e) { throw new UnsupportedOperationException(); }
        @Override public Optional<StoredVersion> findVersion(UUID d, int n) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<StoredVersion> listVersions(UUID d, int p, int s) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<StoredVersion> listVersionsFromOffset(UUID d, int o, int s) { throw new UnsupportedOperationException(); }
        @Override public long countVersions(UUID d) { throw new UnsupportedOperationException(); }
        @Override public VersionDiffResult diff(UUID d, int a, int b) { throw new UnsupportedOperationException(); }
        @Override public Map<String, Object> loadVersionBody(UUID d, int n) { throw new UnsupportedOperationException(); }
        @Override public java.time.Instant lastContentModifiedAt(UUID d, java.time.Instant c) { throw new UnsupportedOperationException(); }
    }
}
