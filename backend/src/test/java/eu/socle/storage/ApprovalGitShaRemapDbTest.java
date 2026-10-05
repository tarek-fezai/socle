// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.document.ApprovalActivitiesImpl;
import eu.socle.document.ApprovalRoleResolver;
import eu.socle.document.DocumentVersionRepository;
import eu.socle.document.ReliabilityScoreService;
import eu.socle.testsupport.MigratedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Purge Git d'un autre document : remappage SHA incluant {@code submitted_git_head_sha} ;
 * approbation nominale vs invalidation si le contenu a réellement changé.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("eu.socle.testsupport.MigratedPostgres#dockerAvailable")
class ApprovalGitShaRemapDbTest {

    @Container
    static PostgreSQLContainer<?> postgres = MigratedPostgres.newContainer();

    static JdbcTemplate jdbc;

    static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID SPACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID REQ = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID WF = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @TempDir Path gitDir;

    GitDocumentStore gitStore;
    DocumentHistoryPurgeService purgeService;
    ApprovalActivitiesImpl activities;
    AuditService audit;

    @BeforeAll
    static void migrate() {
        jdbc = MigratedPostgres.migrate(postgres);
        jdbc.update("INSERT INTO spaces (id, name) VALUES (?, 'S')", SPACE);
        jdbc.update("""
                INSERT INTO users (id, email, display_name) VALUES (?, 'a@test', 'A')
                ON CONFLICT (id) DO NOTHING
                """, AUTHOR);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("""
                INSERT INTO approval_workflows (id, name, scope_space_id, status, created_at)
                VALUES (?, 'W', ?, 'active', now())
                ON CONFLICT (id) DO NOTHING
                """, WF, SPACE);
        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        gitStore = new GitDocumentStore(versions, gitDir.resolve("repo"));
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(jdbc.getDataSource());
        GitShaRemappingService shaRemap = new GitShaRemappingService(jdbc, txManager);
        gitStore.setShaRemappingService(shaRemap);
        audit = mock(AuditService.class);
        purgeService = new DocumentHistoryPurgeService(gitStore, audit, shaRemap);
        ReliabilityScoreService reliability = mock(ReliabilityScoreService.class);
        activities = new ApprovalActivitiesImpl(
                jdbc, audit, reliability, gitStore, new ObjectMapper(), mock(ApprovalRoleResolver.class));

        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM documents");

        gitStore.createContent(DOC_A, body("A v1"), AUTHOR);
        upsertDoc(DOC_A, 1, headSha());
        gitStore.createContent(DOC_B, body("B v1"), AUTHOR);
        gitStore.writeCurrentContent(DOC_A, body("A v2"), AUTHOR, AUTHOR, "a2", headSha());
        upsertDoc(DOC_A, 2, headSha());
        gitStore.writeCurrentContent(DOC_B, body("B v2"), AUTHOR, AUTHOR, "b2", headSha());
        gitStore.writeCurrentContent(DOC_A, body("A v3"), AUTHOR, AUTHOR, "a3", headSha());
        upsertDoc(DOC_A, 3, headSha());
    }

    @AfterEach
    void tearDown() {
        gitStore.close();
        new org.eclipse.jgit.storage.file.WindowCacheConfig().install();
    }

    @Test
    void purgeOtherDocument_doesNotInvalidateApproval_whenContentUnchanged() {
        activities.recordSubmission(DOC_A, AUTHOR, REQ, WF, "wf-1", 1, 24);
        String submittedBefore = jdbc.queryForObject(
                "SELECT submitted_git_head_sha FROM approval_requests WHERE id = ?", String.class, REQ);

        purgeService.purgeNow(null, List.of(DOC_B));

        String submittedAfter = jdbc.queryForObject(
                "SELECT submitted_git_head_sha FROM approval_requests WHERE id = ?", String.class, REQ);
        String currentHead = jdbc.queryForObject(
                "SELECT git_head_sha FROM documents WHERE id = ?", String.class, DOC_A);
        assertThat(submittedAfter).isNotEqualTo(submittedBefore);
        assertThat(submittedAfter).isEqualTo(currentHead);

        String decision = activities.recordFinalDecision(DOC_A, REQ, 1, "approuve", AUTHOR, "ok");
        assertThat(decision).isEqualTo("approuve");
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE id = ?", String.class, DOC_A))
                .isEqualTo("valide");
        verify(audit).recordSync(
                eq(AUTHOR), eq(false), eq(AuditActions.DOCUMENT_APPROVED),
                eq("document"), eq(DOC_A), any(), eq(null));
    }

    @Test
    void approve_afterRealContentChange_stillInvalidates() {
        activities.recordSubmission(DOC_A, AUTHOR, REQ, WF, "wf-1", 1, 24);
        purgeService.purgeNow(null, List.of(DOC_B));

        assertThat(jdbc.queryForObject(
                "SELECT submitted_git_head_sha FROM approval_requests WHERE id = ?", String.class, REQ))
                .isEqualTo(jdbc.queryForObject("SELECT git_head_sha FROM documents WHERE id = ?", String.class, DOC_A));

        String newHead = gitStore.writeCurrentContent(
                DOC_A, body("Doc A mutated"), AUTHOR, AUTHOR, "mut",
                jdbc.queryForObject("SELECT git_head_sha FROM documents WHERE id = ?", String.class, DOC_A));
        jdbc.update("UPDATE documents SET git_head_sha = ?, current_version_no = 2 WHERE id = ?", newHead, DOC_A);

        String decision = activities.recordFinalDecision(DOC_A, REQ, 1, "approuve", AUTHOR, "ok");
        assertThat(decision).isEqualTo("annule");
        verify(audit, never()).recordSync(
                any(), any(Boolean.class), eq(AuditActions.DOCUMENT_APPROVED),
                any(), any(), any(), any());
    }

    private void upsertDoc(UUID docId, int versionNo, String headSha) {
        jdbc.update("""
                INSERT INTO documents (id, space_id, title, body, status, current_version_no, git_head_sha)
                VALUES (?, ?, 'T', '{}'::jsonb, 'brouillon', ?, ?)
                ON CONFLICT (id) DO UPDATE
                  SET git_head_sha = EXCLUDED.git_head_sha, current_version_no = EXCLUDED.current_version_no
                """, docId, SPACE, versionNo, headSha);
    }

    private String headSha() {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(gitDir.resolve("repo").toFile())) {
            var id = git.getRepository().resolve("HEAD");
            return id == null ? null : id.name();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> body(String text) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "text");
        t.put("text", text);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "paragraph");
        p.put("content", List.of(t));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "doc");
        d.put("content", List.of(p));
        return d;
    }
}
