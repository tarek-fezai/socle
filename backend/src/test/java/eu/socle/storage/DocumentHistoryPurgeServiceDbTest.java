// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.storage.DocumentStore.HistoryPurgeResult;
import eu.socle.testsupport.MigratedPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Resynchronisation des SHA en base après réécriture Git + file durable après commit SQL. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("eu.socle.testsupport.MigratedPostgres#dockerAvailable")
class DocumentHistoryPurgeServiceDbTest {

    @Container
    static PostgreSQLContainer<?> postgres = MigratedPostgres.newContainer();

    static JdbcTemplate jdbc;

    static final UUID PURGED = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID KEPT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID SPACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID APPROVAL = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID WORKFLOW = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    AuditService audit;
    FakeStore store;
    DocumentHistoryPurgeService service;
    GitPurgeQueueService queueService;
    DataSourceTransactionManager txManager;

    @BeforeAll
    static void migrate() {
        jdbc = MigratedPostgres.migrate(postgres);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM git_purge_queue");
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM documents");
        insertDoc(KEPT, "sha-kept-old");
        jdbc.update("INSERT INTO document_versions (document_id, version_no, body_snapshot, git_commit_sha) "
                + "VALUES (?, 1, '{}'::jsonb, 'sha-v1-old'), (?, 2, '{}'::jsonb, 'sha-v2-same')", KEPT, KEPT);
        jdbc.update("""
                INSERT INTO approval_workflows (id, name, scope_space_id, status, created_at)
                VALUES (?, 'W', ?, 'active', now())
                ON CONFLICT (id) DO NOTHING
                """, WORKFLOW, SPACE);
        UUID requester = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        jdbc.update("""
                INSERT INTO users (id, email, display_name) VALUES (?, 'r@test', 'R')
                ON CONFLICT (id) DO NOTHING
                """, requester);
        jdbc.update("""
                INSERT INTO approval_requests (id, document_id, workflow_id, temporal_workflow_id,
                    requested_by, status, submitted_content_version_no, submitted_git_head_sha, current_step_order)
                VALUES (?, ?, ?, 'wf-test', ?, 'en_cours', 2, 'sha-kept-old', 1)
                """, APPROVAL, KEPT, WORKFLOW, requester);

        audit = mock(AuditService.class);
        store = new FakeStore();
        txManager = new DataSourceTransactionManager(jdbc.getDataSource());
        GitShaRemappingService shaRemap = new GitShaRemappingService(jdbc, txManager);
        service = new DocumentHistoryPurgeService(store, audit, shaRemap);
        queueService = new GitPurgeQueueService(jdbc, service, audit);
        @SuppressWarnings("unchecked")
        ObjectProvider<GitPurgeQueueService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(queueService);
        service = new DocumentHistoryPurgeService(store, audit, shaRemap, provider);
    }

    @Test
    void purgeNow_remapsAllShaColumns_includingApprovalFingerprint() {
        store.result = new HistoryPurgeResult(Set.of(PURGED), 3, 1, "sha-kept-old", "sha-kept-new",
                Map.of("sha-kept-old", "sha-kept-new", "sha-v1-old", "sha-v1-new", "sha-v2-same", "sha-v2-same"),
                null);

        Optional<HistoryPurgeResult> r = service.purgeNow(null, Set.of(PURGED));

        assertThat(r).isPresent();
        assertThat(jdbc.queryForObject("SELECT git_head_sha FROM documents WHERE id = ?", String.class, KEPT))
                .isEqualTo("sha-kept-new");
        assertThat(jdbc.queryForList(
                "SELECT git_commit_sha FROM document_versions WHERE document_id = ? ORDER BY version_no",
                String.class, KEPT)).containsExactly("sha-v1-new", "sha-v2-same");
        assertThat(jdbc.queryForObject(
                "SELECT submitted_git_head_sha FROM approval_requests WHERE id = ?", String.class, APPROVAL))
                .isEqualTo("sha-kept-new");
        assertThat(store.remapUnderWriteLock.get()).isEqualTo(1);

        verify(audit).record(isNull(), eq(true), eq(AuditActions.DOCUMENT_GIT_HISTORY_PURGED),
                eq("document"), eq(PURGED),
                org.mockito.ArgumentMatchers.argThat(m -> "sha-kept-new".equals(m.get("newHead"))),
                isNull());
    }

    @Test
    void purgeNow_withoutRewrite_changesNothing() {
        store.result = null;
        assertThat(service.purgeNow(null, Set.of(PURGED))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT git_head_sha FROM documents WHERE id = ?", String.class, KEPT))
                .isEqualTo("sha-kept-old");
        verify(audit, never()).record(any(), any(Boolean.class), eq(AuditActions.DOCUMENT_GIT_HISTORY_PURGED),
                any(), any(), anyMap(), any());
    }

    @Test
    void purgeAfterCommit_enqueuesAndRunsAfterSqlCommit() {
        store.result = new HistoryPurgeResult(Set.of(PURGED), 1, 0, "a", "b", Map.of(), null);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID second = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            service.purgeAfterCommit(null, PURGED);
            service.purgeAfterCommit(null, second);
            assertThat(store.calls.get()).as("rien avant le commit").isZero();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_purge_queue", Long.class)).isEqualTo(1);
        assertThat(store.calls.get()).as("un seul passage pour le lot").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM git_purge_queue", String.class)).isEqualTo("done");
    }

    @Test
    void purgeAfterCommit_rolledBackTransaction_neverEnqueues() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(status -> {
            service.purgeAfterCommit(null, PURGED);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM git_purge_queue", Long.class)).isZero();
        assertThat(store.calls.get()).isZero();
    }

    static void insertDoc(UUID id, String headSha) {
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, git_head_sha)
                VALUES (?, ?, 'D', '{"type":"doc"}'::jsonb, ?)
                """, id, SPACE, headSha);
    }

    static final class FakeStore implements DocumentStore {
        HistoryPurgeResult result;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger remapUnderWriteLock = new AtomicInteger();

        @Override
        public Optional<HistoryPurgeResult> purgeDocumentsHistory(Collection<UUID> documentIds) {
            return purgeDocumentsHistory(documentIds, null);
        }

        @Override
        public Optional<HistoryPurgeResult> purgeDocumentsHistory(
                Collection<UUID> documentIds,
                Consumer<Map<String, String>> remapBeforeWriteLockReleased
        ) {
            calls.incrementAndGet();
            if (result == null) {
                return Optional.empty();
            }
            if (remapBeforeWriteLockReleased != null) {
                remapUnderWriteLock.incrementAndGet();
                remapBeforeWriteLockReleased.accept(result.commitMapping());
            }
            return Optional.of(result);
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
