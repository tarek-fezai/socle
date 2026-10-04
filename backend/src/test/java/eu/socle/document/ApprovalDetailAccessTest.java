// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentApprovalService.ApprovalDetailView;
import eu.socle.document.DocumentRelatedLinksService.ImpactedIncoming;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalDetailAccessTest {

    static final UUID APPROVER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID REQUESTER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID CONTRIBUTOR = UUID.fromString("66666666-6666-6666-6666-666666666666");
    static final UUID STRANGER = UUID.fromString("77777777-7777-7777-7777-777777777777");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID REQ = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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
        when(relatedLinksService.impactedIncoming(any(), eq(DOC)))
                .thenReturn(new ImpactedIncoming(List.of(), 0));
        stubEmptyContributors();
        stubLoadRequest("en_cours", REQUESTER);
    }

    @Test
    void requester_canRead_cannotDecide() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(REQUESTER));
        when(authorizationService.hasRelation(REQUESTER, "document", DOC, "viewer")).thenReturn(true);

        ApprovalDetailView d = service.getApproval(jwt(REQUESTER), REQ);

        assertThat(d.canDecide()).isFalse();
        assertThat(d.cannotDecideReason()).isEqualTo("requester");
    }

    @Test
    void contributor_canRead_cannotDecide() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(CONTRIBUTOR));
        when(authorizationService.hasRelation(CONTRIBUTOR, "document", DOC, "viewer")).thenReturn(true);
        when(jdbcTemplate.query(
                        contains("document_versions dv"),
                        any(RowMapper.class),
                        any(), any()))
                .thenReturn(List.of(CONTRIBUTOR));

        ApprovalDetailView d = service.getApproval(jwt(CONTRIBUTOR), REQ);

        assertThat(d.canDecide()).isFalse();
        assertThat(d.cannotDecideReason()).isEqualTo("contributor");
    }

    @Test
    void nonViewer_404() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(STRANGER));
        when(authorizationService.hasRelation(STRANGER, "document", DOC, "viewer")).thenReturn(false);

        assertThatThrownBy(() -> service.getApproval(jwt(STRANGER), REQ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void currentApprover_canDecide() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(APPROVER));
        when(authorizationService.hasRelation(APPROVER, "document", DOC, "viewer")).thenReturn(true);
        when(authorizationService.hasRelation(APPROVER, "document", DOC, "editor")).thenReturn(true);
        when(approvalRoleResolver.canDecideCurrentStep(APPROVER, REQ)).thenReturn(true);

        ApprovalDetailView d = service.getApproval(jwt(APPROVER), REQ);

        assertThat(d.canDecide()).isTrue();
        assertThat(d.cannotDecideReason()).isNull();
    }

    @Test
    void resolved_cannotDecide() {
        stubLoadRequest("approuve", REQUESTER);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(APPROVER));
        when(authorizationService.hasRelation(APPROVER, "document", DOC, "viewer")).thenReturn(true);

        ApprovalDetailView d = service.getApproval(jwt(APPROVER), REQ);

        assertThat(d.canDecide()).isFalse();
        assertThat(d.cannotDecideReason()).isEqualTo("resolved");
    }

    private void stubLoadRequest(String status, UUID requestedBy) {
        when(jdbcTemplate.query(contains("WHERE ar.id = ?"), any(RowMapper.class), eq(REQ)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(REQ);
                    when(rs.getObject("document_id")).thenReturn(DOC);
                    when(rs.getString("document_title")).thenReturn("Doc");
                    when(rs.getString("temporal_workflow_id")).thenReturn("wf");
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getInt("current_step_order")).thenReturn(2);
                    when(rs.getTimestamp("sla_deadline_at")).thenReturn(null);
                    when(rs.getObject("submitted_version_no")).thenReturn(3);
                    when(rs.getObject("baseline_version_no")).thenReturn(2);
                    when(rs.getObject("requested_by")).thenReturn(requestedBy);
                    when(rs.getString("requested_by_display_name")).thenReturn("Claire");
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

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(sub.toString()).build();
    }
}
