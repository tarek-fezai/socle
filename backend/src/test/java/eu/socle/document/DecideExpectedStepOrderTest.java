// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DecideExpectedStepOrderTest {

    static final UUID APPROVER = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
    @Mock DocumentApprovalWorkflow workflowStub;

    DocumentApprovalService service;
    AtomicBoolean signalSent;

    @BeforeEach
    void setUp() {
        signalSent = new AtomicBoolean(false);
        service = new DocumentApprovalService(
                workflowClient,
                documentRepository,
                userSyncService,
                authorizationService,
                jdbcTemplate,
                activities,
                workflowDefinitions,
                approvalRoleResolver,
                passthroughTx(),
                false
        );
        when(userSyncService.syncFromJwt(any())).thenReturn(user(APPROVER));
        when(authorizationService.hasRelation(APPROVER, "document", DOC, "editor")).thenReturn(true);
        when(approvalRoleResolver.canDecideCurrentStep(eq(APPROVER), any())).thenReturn(true);
        when(workflowClient.newWorkflowStub(eq(DocumentApprovalWorkflow.class), eq("wf-1")))
                .thenReturn(workflowStub);
        org.mockito.Mockito.doAnswer(inv -> {
            signalSent.set(true);
            return null;
        }).when(workflowStub).decide(any(), any(), any());
    }

    @Test
    void decide_matchingExpectedStep_signalsTemporal() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(REQ)))
                .thenReturn(List.of(Map.of(
                        "temporal_workflow_id", "wf-1",
                        "status", "en_cours",
                        "document_id", DOC,
                        "current_step_order", 1
                )));
        when(jdbcTemplate.queryForObject(contains("SELECT status FROM approval_requests"), eq(String.class), eq(REQ)))
                .thenReturn("approuve");
        when(jdbcTemplate.queryForObject(contains("sla_deadline_at IS NULL"), eq(Boolean.class), eq(REQ)))
                .thenReturn(false);

        Map<String, String> result = service.decide(
                jwt(APPROVER), DOC, REQ,
                new DocumentApprovalService.DecisionRequest("approuve", "ok", 1));

        assertThat(result.get("status")).isEqualTo("approuve");
        assertThat(signalSent).isTrue();
        verify(workflowStub).decide("approuve", APPROVER, "ok");
    }

    @Test
    void decide_obsoleteExpectedStep_returnsStepAdvanced_andDoesNotSignal() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(REQ)))
                .thenReturn(List.of(Map.of(
                        "temporal_workflow_id", "wf-1",
                        "status", "en_cours",
                        "document_id", DOC,
                        "current_step_order", 2
                )));

        assertThatThrownBy(() -> service.decide(
                jwt(APPROVER), DOC, REQ,
                new DocumentApprovalService.DecisionRequest("approuve", null, 1)))
                .isInstanceOf(ApprovalConflictException.class)
                .satisfies(ex -> {
                    ApprovalConflictException ace = (ApprovalConflictException) ex;
                    assertThat(ace.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ace.getError()).isEqualTo(ApprovalConflictException.STEP_ADVANCED);
                });

        assertThat(signalSent).isFalse();
        verify(workflowClient, never()).newWorkflowStub(any(), any(String.class));
    }

    @Test
    void decide_alreadyResolved_returnsAlreadyResolved_distinctFromStepAdvanced() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(REQ)))
                .thenReturn(List.of(Map.of(
                        "temporal_workflow_id", "wf-1",
                        "status", "approuve",
                        "document_id", DOC,
                        "current_step_order", 1
                )));

        assertThatThrownBy(() -> service.decide(
                jwt(APPROVER), DOC, REQ,
                new DocumentApprovalService.DecisionRequest("approuve", null, 1)))
                .isInstanceOf(ApprovalConflictException.class)
                .satisfies(ex -> {
                    ApprovalConflictException ace = (ApprovalConflictException) ex;
                    assertThat(ace.getError()).isEqualTo(ApprovalConflictException.ALREADY_RESOLVED);
                    assertThat(ace.getError()).isNotEqualTo(ApprovalConflictException.STEP_ADVANCED);
                });

        assertThat(signalSent).isFalse();
        verify(workflowClient, never()).newWorkflowStub(any(), any(String.class));
    }

    @Test
    void decide_missingExpectedStepOrder_badRequest() {
        assertThatThrownBy(() -> service.decide(
                jwt(APPROVER), DOC, REQ,
                new DocumentApprovalService.DecisionRequest("approuve", null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(workflowClient, never()).newWorkflowStub(any(), any(String.class));
    }

    static TransactionTemplate passthroughTx() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            }
        });
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("a@example.com");
        u.setDisplayName("A");
        u.setStatus("active");
        u.setSystemAccount(false);
        return u;
    }

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
