// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditService;
import eu.socle.storage.DocumentStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ÉTAPE 1 — soumission sans version dupliquée ; baseline dual-schéma ; quatre yeux.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class ApprovalSubmissionNoBumpTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;

    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID CAROL = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID WF = UUID.fromString("44444444-4444-4444-4444-444444444444");

    DocumentStore store;
    ApprovalActivitiesImpl activities;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY,
                  space_id UUID,
                  title TEXT NOT NULL,
                  body JSONB NOT NULL DEFAULT '{}'::jsonb,
                  status TEXT NOT NULL DEFAULT 'brouillon',
                  current_version_no INT NOT NULL DEFAULT 1,
                  current_change_summary TEXT,
                  git_head_sha TEXT,
                  created_by UUID,
                  updated_by UUID,
                  deleted_at TIMESTAMPTZ,
                  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
        jdbc.execute("""
                CREATE TABLE document_versions (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  version_no INT NOT NULL,
                  author_id UUID,
                  change_summary TEXT
                )""");
        jdbc.execute("""
                CREATE TABLE approval_requests (
                  id UUID PRIMARY KEY,
                  document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                  workflow_id UUID,
                  temporal_workflow_id TEXT,
                  requested_by UUID,
                  current_step_order INT NOT NULL DEFAULT 1,
                  status TEXT NOT NULL DEFAULT 'en_cours',
                  sla_deadline_at TIMESTAMPTZ,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  resolved_at TIMESTAMPTZ,
                  submitted_version_no INT,
                  submitted_content_version_no INT,
                  submitted_git_head_sha TEXT
                )""");
        jdbc.execute("""
                CREATE TABLE notifications (
                  id UUID PRIMARY KEY, user_id UUID NOT NULL, type TEXT NOT NULL,
                  payload JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM document_versions");
        jdbc.update("DELETE FROM documents");
        store = mock(DocumentStore.class);
        activities = new ApprovalActivitiesImpl(
                jdbc, mock(AuditService.class), mock(ReliabilityScoreService.class),
                store, new ObjectMapper(), mock(ApprovalRoleResolver.class));
    }

    @Test
    void submission_doesNotCreateVersion_keepsSummaryAndHead() {
        insertDoc(3, "résumé réel", "sha-abc", ALICE);
        archive(1, ALICE);
        archive(2, BOB);

        UUID req = UUID.randomUUID();
        String status = activities.recordSubmission(DOC, ALICE, req, WF, "wf-1", 1, 24);

        assertThat(status).isEqualTo("en_cours");
        Map<String, Object> doc = jdbc.queryForMap("SELECT * FROM documents WHERE id = ?", DOC);
        assertThat(doc.get("status")).isEqualTo("en_revue");
        assertThat(((Number) doc.get("current_version_no")).intValue()).isEqualTo(3);
        assertThat(doc.get("current_change_summary")).isEqualTo("résumé réel");
        assertThat(doc.get("git_head_sha")).isEqualTo("sha-abc");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_versions WHERE document_id = ?", Integer.class, DOC))
                .isEqualTo(2);

        Map<String, Object> ar = jdbc.queryForMap("SELECT * FROM approval_requests WHERE id = ?", req);
        assertThat(((Number) ar.get("submitted_version_no")).intValue()).isEqualTo(3);
        assertThat(((Number) ar.get("submitted_content_version_no")).intValue()).isEqualTo(3);
        assertThat(ar.get("submitted_git_head_sha")).isEqualTo("sha-abc");

        verify(store, never()).archiveVersion(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(store, never()).writeCurrentContent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void baseline_newSchema_submittedEqualsCurrent() {
        insertDoc(3, "edit", null, ALICE);
        archive(1, ALICE);
        archive(2, BOB);
        UUID req = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approval_requests
                  (id, document_id, status, requested_by, submitted_version_no,
                   submitted_content_version_no, created_at)
                VALUES (?, ?, 'en_cours', ?, 3, 3, now())
                """, req, DOC, ALICE);

        Integer baseline = jdbc.queryForObject(
                "SELECT " + DocumentApprovalService.BASELINE_VERSION_SQL.replace(" AS baseline_version_no", "")
                        + " FROM approval_requests ar WHERE ar.id = ?",
                Integer.class, req);
        assertThat(baseline).isEqualTo(2);
    }

    @Test
    void baseline_legacySchema_submittedIsArchivedBeforeEmptyBump() {
        // Ancien schéma : contenu réel archivé en v3, courant v4 « Soumission… »
        insertDoc(4, "Soumission pour approbation", "newsha", ALICE);
        archive(1, ALICE);
        archive(2, BOB);
        archive(3, ALICE);
        UUID req = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approval_requests
                  (id, document_id, status, requested_by, submitted_version_no, created_at)
                VALUES (?, ?, 'en_cours', ?, 3, now())
                """, req, DOC, ALICE);

        Integer baseline = jdbc.queryForObject(
                "SELECT " + DocumentApprovalService.BASELINE_VERSION_SQL.replace(" AS baseline_version_no", "")
                        + " FROM approval_requests ar WHERE ar.id = ?",
                Integer.class, req);
        assertThat(baseline).isEqualTo(2);
    }

    @Test
    void fourEyes_excludesRequesterAndContentContributors_afterNewSubmission() {
        insertDoc(3, "edit", null, ALICE);
        archive(1, ALICE);
        archive(2, BOB);
        activities.recordSubmission(DOC, ALICE, UUID.randomUUID(), WF, "wf-1", 1, 24);

        Set<UUID> contributors = FourEyesPolicy.loadContentContributors(jdbc, DOC);
        assertThat(contributors).contains(ALICE, BOB);
        assertThat(FourEyesPolicy.isConflict(ALICE, ALICE, contributors)).isTrue();
        assertThat(FourEyesPolicy.isConflict(BOB, ALICE, contributors)).isTrue();
        assertThat(FourEyesPolicy.isConflict(CAROL, ALICE, contributors)).isFalse();
    }

    private void insertDoc(int versionNo, String summary, String gitSha, UUID updatedBy) {
        jdbc.update("""
                INSERT INTO documents
                  (id, space_id, title, body, status, current_version_no, current_change_summary,
                   git_head_sha, created_by, updated_by)
                VALUES (?, ?, 'Doc', '{}'::jsonb, 'brouillon', ?, ?, ?, ?, ?)
                """, DOC, SPACE, versionNo, summary, gitSha, ALICE, updatedBy);
    }

    private void archive(int versionNo, UUID author) {
        jdbc.update("""
                INSERT INTO document_versions (document_id, version_no, author_id, change_summary)
                VALUES (?, ?, ?, ?)
                """, DOC, versionNo, author, "v" + versionNo);
    }
}
