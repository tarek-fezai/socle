// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentApprovalService.ApprovalDetailView;
import eu.socle.document.DocumentRelatedLinksService.ImpactedIncoming;
import eu.socle.document.DocumentRelatedLinksService.LinkedDocument;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Liens impactés = entrants (A, B → D) ; C (sortant D → C) absent ;
 * B non visible → hiddenImpactedCount, jamais son titre dans le JSON.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalImpactedLinksTest {

    static final UUID APPROVER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID REQUESTER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID DOC_D = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID DOC_A = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID DOC_B = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID DOC_C = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID REQ = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Mock WorkflowClient workflowClient;
    @Mock DocumentRepository documentRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock ApprovalActivitiesImpl activities;
    @Mock ApprovalWorkflowDefinitionService workflowDefinitions;
    @Mock ApprovalRoleResolver approvalRoleResolver;
    @Mock DocumentRelatedLinksService relatedLinksService;

    DocumentApprovalService service;

    @BeforeEach
    void setUp() {
        service = new DocumentApprovalService(
                workflowClient, documentRepository, userSyncService, authorizationService,
                jdbcTemplate, activities, workflowDefinitions, approvalRoleResolver,
                new TransactionTemplate(), relatedLinksService, false);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(APPROVER));
        when(authorizationService.hasRelation(APPROVER, "document", DOC_D, "viewer")).thenReturn(true);
        when(authorizationService.hasRelation(APPROVER, "document", DOC_D, "editor")).thenReturn(true);
        when(approvalRoleResolver.canDecideCurrentStep(APPROVER, REQ)).thenReturn(true);
        stubEmptyContributors();
        stubLoadRequest();
    }

    @Test
    void getApproval_impactedLinks_areIncomingOnly_withHiddenCount() {
        when(relatedLinksService.impactedIncoming(any(), eq(DOC_D))).thenReturn(new ImpactedIncoming(
                List.of(new LinkedDocument(DOC_A, "Document A")),
                1));

        ApprovalDetailView detail = service.getApproval(jwt(), REQ);

        assertThat(detail.impactedLinks()).extracting(DocumentApprovalService.ImpactedLink::id)
                .containsExactly(DOC_A);
        assertThat(detail.impactedLinks()).extracting(DocumentApprovalService.ImpactedLink::title)
                .containsExactly("Document A");
        assertThat(detail.hiddenImpactedCount()).isEqualTo(1);
        assertThat(detail.impactedLinks()).extracting(DocumentApprovalService.ImpactedLink::id)
                .doesNotContain(DOC_B, DOC_C);
        String jsonShape = detail.impactedLinks().toString() + detail.hiddenImpactedCount();
        assertThat(jsonShape).doesNotContain("Document B").doesNotContain(DOC_B.toString());
        assertThat(jsonShape).doesNotContain("Document C").doesNotContain(DOC_C.toString());
        verify(relatedLinksService).impactedIncoming(any(), eq(DOC_D));
    }

    @Test
    void listMine_doesNotResolveImpactedLinks() {
        when(jdbcTemplate.query(contains("approval_requests ar"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(REQ);
                    when(rs.getObject("document_id")).thenReturn(DOC_D);
                    when(rs.getString("document_title")).thenReturn("D");
                    when(rs.getString("temporal_workflow_id")).thenReturn("wf");
                    when(rs.getString("status")).thenReturn("en_cours");
                    when(rs.getInt("current_step_order")).thenReturn(1);
                    when(rs.getTimestamp("sla_deadline_at")).thenReturn(null);
                    when(rs.getObject("submitted_version_no")).thenReturn(2);
                    when(rs.getObject("baseline_version_no")).thenReturn(1);
                    when(rs.getObject("requested_by")).thenReturn(REQUESTER);
                    when(rs.getString("requested_by_display_name")).thenReturn("Claire");
                    when(rs.getString("requested_by_initials")).thenReturn("CD");
                    when(rs.getTimestamp("created_at")).thenReturn(null);
                    when(rs.getObject("approver_role_id")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(approvalRoleResolver.canDecide(eq(APPROVER), any(), eq(DOC_D))).thenReturn(true);

        var mine = service.listMine(jwt());

        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().impactedLinks()).isEmpty();
        assertThat(mine.getFirst().hiddenImpactedCount()).isZero();
        org.mockito.Mockito.verifyNoInteractions(relatedLinksService);
    }

    private void stubLoadRequest() {
        when(jdbcTemplate.query(contains("WHERE ar.id = ?"), any(RowMapper.class), eq(REQ)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(REQ);
                    when(rs.getObject("document_id")).thenReturn(DOC_D);
                    when(rs.getString("document_title")).thenReturn("Document D");
                    when(rs.getString("temporal_workflow_id")).thenReturn("wf-1");
                    when(rs.getString("status")).thenReturn("en_cours");
                    when(rs.getInt("current_step_order")).thenReturn(1);
                    when(rs.getTimestamp("sla_deadline_at")).thenReturn(null);
                    when(rs.getObject("submitted_version_no")).thenReturn(3);
                    when(rs.getObject("baseline_version_no")).thenReturn(2);
                    when(rs.getObject("requested_by")).thenReturn(REQUESTER);
                    when(rs.getString("requested_by_display_name")).thenReturn("Claire Dubois");
                    when(rs.getString("requested_by_initials")).thenReturn("CD");
                    when(rs.getTimestamp("created_at")).thenReturn(null);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }

    private void stubEmptyContributors() {
        org.mockito.Mockito.lenient().when(jdbcTemplate.query(
                        contains("submitted_version_no"),
                        any(org.springframework.jdbc.core.ResultSetExtractor.class),
                        any()))
                .thenReturn(null);
        org.mockito.Mockito.lenient().when(jdbcTemplate.query(
                        contains("document_versions dv"),
                        any(RowMapper.class),
                        any(), any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(jdbcTemplate.query(
                        contains("COALESCE(updated_by"),
                        any(RowMapper.class),
                        any()))
                .thenReturn(List.of());
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@example.com");
        return u;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(APPROVER.toString()).build();
    }
}
