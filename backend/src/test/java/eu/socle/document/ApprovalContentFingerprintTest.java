// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.storage.DocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ÉTAPE 0 — empreinte de contenu soumis : approbation nominal vs invalidation si dérive.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalContentFingerprintTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID REQ = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID REQUESTER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock JdbcTemplate jdbc;
    @Mock AuditService audit;
    @Mock ReliabilityScoreService reliability;
    @Mock DocumentStore store;

    ApprovalActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        when(store.writeCurrentContent(any(), any(), any(), any(), any(), any())).thenReturn("head-submitted");
        activities = new ApprovalActivitiesImpl(
                jdbc, audit, reliability, store, new ObjectMapper(), mock(ApprovalRoleResolver.class));
        when(jdbc.update(anyString(), any(Object.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object.class), any(Object.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class)))
                .thenReturn(1);
    }

    @Test
    void recordSubmission_storesContentFingerprint() {
        Map<String, Object> docRow = new HashMap<>();
        docRow.put("current_version_no", 3);
        docRow.put("body", Map.of("type", "doc"));
        docRow.put("git_head_sha", "old");
        docRow.put("updated_by", ACTOR);
        docRow.put("created_by", ACTOR);
        docRow.put("current_change_summary", "edit");
        when(jdbc.queryForList(contains("current_version_no"), eq(DOC))).thenReturn(List.of(docRow));

        activities.recordSubmission(DOC, ACTOR, REQ, UUID.randomUUID(), "wf-1", 1, 24);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        // INSERT avec empreinte : contentVersionNo=4, newHead=head-submitted
        verify(jdbc).update(
                contains("submitted_content_version_no"),
                eq(REQ), eq(DOC), any(), eq("wf-1"), eq(ACTOR),
                eq(1), eq(24), eq(3), eq(4), eq("head-submitted"));
    }

    @Test
    void approve_whenFingerprintMatches_setsValide() {
        stubFingerprint(4, "head-submitted", 4, "head-submitted");

        String result = activities.recordFinalDecision(DOC, REQ, 1, "approuve", ACTOR, "ok");

        assertThat(result).isEqualTo("approuve");
        verify(jdbc).update(contains("SET status = ?"), eq("approuve"), eq(REQ));
        verify(jdbc).update(contains("UPDATE documents SET status"), eq("valide"), eq(DOC));
        verify(audit).recordSync(
                eq(ACTOR), eq(false), eq(AuditActions.DOCUMENT_APPROVED),
                eq("document"), eq(DOC), anyMap(), isNull());
        verify(reliability).requestRecalculationAfterCommit(DOC);
        verify(jdbc, never()).update(contains("approval_invalidated"), any(), any(), any());
    }

    @Test
    void approve_whenContentMutated_cancelsAndRevertsToBrouillon() {
        stubFingerprint(4, "head-submitted", 5, "head-mutated");
        when(jdbc.queryForObject(contains("requested_by"), eq(UUID.class), eq(REQ)))
                .thenReturn(REQUESTER);

        String result = activities.recordFinalDecision(DOC, REQ, 1, "approuve", ACTOR, "ok");

        assertThat(result).isEqualTo("annule");
        verify(jdbc).update(contains("status = 'annule'"), eq(REQ));
        verify(jdbc).update(contains("status = 'brouillon'"), eq(DOC));
        verify(audit).recordSync(
                eq(ACTOR), eq(false), eq(AuditActions.DOCUMENT_APPROVAL_INVALIDATED),
                eq("document"), eq(DOC), anyMap(), isNull());
        verify(jdbc).update(
                contains("approval_invalidated"),
                any(UUID.class), eq(REQUESTER), anyString());
        verify(reliability).clearScoreInDb(DOC);
        verify(reliability, never()).requestRecalculationAfterCommit(any());
        verify(audit, never()).recordSync(
                any(), any(Boolean.class), eq(AuditActions.DOCUMENT_APPROVED),
                any(), any(), any(), any());
    }

    @Test
    void approve_withoutFingerprint_preV38_stillApproves() {
        Map<String, Object> row = new HashMap<>();
        row.put("submitted_ver", null);
        row.put("submitted_sha", null);
        row.put("current_ver", 4);
        row.put("current_sha", "x");
        when(jdbc.queryForList(contains("submitted_content_version_no"), eq(REQ), eq(DOC)))
                .thenReturn(List.of(row));

        String result = activities.recordFinalDecision(DOC, REQ, 1, "approuve", ACTOR, "ok");

        assertThat(result).isEqualTo("approuve");
        verify(audit).recordSync(
                eq(ACTOR), eq(false), eq(AuditActions.DOCUMENT_APPROVED),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    private void stubFingerprint(int submittedVer, String submittedSha, int currentVer, String currentSha) {
        Map<String, Object> row = new HashMap<>();
        row.put("submitted_ver", submittedVer);
        row.put("submitted_sha", submittedSha);
        row.put("current_ver", currentVer);
        row.put("current_sha", currentSha);
        when(jdbc.queryForList(contains("submitted_content_version_no"), eq(REQ), eq(DOC)))
                .thenReturn(List.of(row));
    }
}
