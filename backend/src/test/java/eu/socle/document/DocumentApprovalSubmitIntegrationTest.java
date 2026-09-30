package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Intégration auth → authz → workflow (non-régression + ordre Check avant Start).
 * Voir docs/audit-integration-auth-authz-workflow.md.
 */
@ExtendWith(MockitoExtension.class)
class DocumentApprovalSubmitIntegrationTest {

    static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID CONTRIBUTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OUTSIDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock DocumentRepository documentRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock ApprovalActivitiesImpl springActivitiesIgnored;
    @Mock ApprovalWorkflowDefinitionService workflowDefinitions;

    TestWorkflowEnvironment testEnv;
    WorkflowClient workflowClient;
    RecordingActivities recordingActivities;
    DocumentApprovalService service;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        workflowClient = testEnv.getWorkflowClient();
        recordingActivities = new RecordingActivities(List.of(new ApprovalStepDef(1, 24, null)));

        Worker worker = testEnv.newWorker(DocumentApprovalService.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DocumentApprovalWorkflowImpl.class);
        worker.registerActivitiesImplementations(recordingActivities);
        testEnv.start();

        service = new DocumentApprovalService(
                workflowClient,
                documentRepository,
                userSyncService,
                authorizationService,
                jdbcTemplate,
                springActivitiesIgnored,
                workflowDefinitions,
                DecideExpectedStepOrderTest.passthroughTx(),
                false
        );
    }

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void negative_jwtValidButNoOpenFgaAccess_returns403_andDoesNotStartTemporalWorkflow() {
        UUID documentId = UUID.randomUUID();
        when(userSyncService.syncFromJwt(any())).thenReturn(user(OUTSIDER_ID, "outsider@example.com"));
        when(documentRepository.findActiveById(documentId)).thenReturn(Optional.of(document(documentId)));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(authorizationService)
                .requireDocumentRelation(OUTSIDER_ID, documentId, "editor");

        assertThatThrownBy(() -> service.startApproval(jwtFor(OUTSIDER_ID, "outsider@example.com"), documentId))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(authorizationService).requireDocumentRelation(OUTSIDER_ID, documentId, "editor");
        verify(authorizationService, never()).requireSpaceRelation(any(), any(), anyString());
        assertThat(recordingActivities.submissions).isEmpty();
    }

    @Test
    void negative_documentEditorOkButNoSpaceAccess_returns403_beforeTemporalStart() {
        UUID documentId = UUID.randomUUID();
        when(userSyncService.syncFromJwt(any())).thenReturn(user(OUTSIDER_ID, "outsider@example.com"));
        when(documentRepository.findActiveById(documentId)).thenReturn(Optional.of(document(documentId)));
        doNothing().when(authorizationService).requireDocumentRelation(OUTSIDER_ID, documentId, "editor");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès espace refusé (viewer)"))
                .when(authorizationService)
                .requireSpaceRelation(OUTSIDER_ID, SPACE_ID, "viewer");

        assertThatThrownBy(() -> service.startApproval(jwtFor(OUTSIDER_ID, "outsider@example.com"), documentId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(recordingActivities.submissions).isEmpty();
        InOrder order = inOrder(authorizationService);
        order.verify(authorizationService).requireDocumentRelation(OUTSIDER_ID, documentId, "editor");
        order.verify(authorizationService).requireSpaceRelation(OUTSIDER_ID, SPACE_ID, "viewer");
    }

    @Test
    void positive_contributorWithFgaAccess_startsTemporalWorkflow_andApprovePersists() {
        UUID documentId = UUID.randomUUID();
        when(userSyncService.syncFromJwt(any())).thenReturn(user(CONTRIBUTOR_ID, "contributeur@example.com"));
        when(documentRepository.findActiveById(documentId)).thenReturn(Optional.of(document(documentId)));
        doNothing().when(authorizationService).requireDocumentRelation(CONTRIBUTOR_ID, documentId, "editor");
        doNothing().when(authorizationService).requireSpaceRelation(CONTRIBUTOR_ID, SPACE_ID, "viewer");

        when(jdbcTemplate.queryForObject(contains("approval_requests WHERE document_id"), eq(Integer.class), eq(documentId)))
                .thenReturn(0);
        UUID workflowDefId = UUID.randomUUID();
        when(workflowDefinitions.resolveId(eq(SPACE_ID), any())).thenReturn(workflowDefId);
        when(jdbcTemplate.queryForObject(contains("FROM approval_requests WHERE id"), eq(Integer.class), any(UUID.class)))
                .thenAnswer(inv -> recordingActivities.submissions.isEmpty() ? 0 : 1);

        InOrder order = inOrder(authorizationService);
        var started = service.startApproval(jwtFor(CONTRIBUTOR_ID, "contributeur@example.com"), documentId);

        order.verify(authorizationService).requireDocumentRelation(CONTRIBUTOR_ID, documentId, "editor");
        order.verify(authorizationService).requireSpaceRelation(CONTRIBUTOR_ID, SPACE_ID, "viewer");

        assertThat(started.status()).isEqualTo("en_cours");
        assertThat(started.temporalWorkflowId()).isNotBlank();

        DescribeWorkflowExecutionResponse described = describe(started.temporalWorkflowId());
        assertThat(described.getWorkflowExecutionInfo().getStatus())
                .isIn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING,
                        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);

        assertThat(recordingActivities.submissions).hasSize(1);
        assertThat(recordingActivities.requestStatus.get()).isEqualTo("en_cours");

        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(started.approvalRequestId())))
                .thenReturn(List.of(Map.of(
                        "temporal_workflow_id", started.temporalWorkflowId(),
                        "status", "en_cours",
                        "document_id", documentId,
                        "current_step_order", 1
                )));
        when(jdbcTemplate.queryForObject(contains("SELECT EXISTS"), eq(Boolean.class), any(), any(), any()))
                .thenReturn(true);
        when(jdbcTemplate.queryForObject(contains("SELECT status FROM approval_requests"), eq(String.class), eq(started.approvalRequestId())))
                .thenAnswer(inv -> recordingActivities.requestStatus.get());
        when(jdbcTemplate.queryForObject(contains("sla_deadline_at IS NULL"), eq(Boolean.class), eq(started.approvalRequestId())))
                .thenReturn(false);

        Map<String, String> decided = service.decide(
                jwtFor(CONTRIBUTOR_ID, "contributeur@example.com"),
                documentId,
                started.approvalRequestId(),
                new DocumentApprovalService.DecisionRequest("approuve", "ok audit", 1)
        );

        assertThat(decided.get("status")).isEqualTo("approuve");
        assertThat(recordingActivities.requestStatus.get()).isEqualTo("approuve");
        assertThat(recordingActivities.finalActions).hasSize(1);
        assertThat(recordingActivities.finalActions.getFirst().decision()).isEqualTo("approuve");

        assertThat(describe(started.temporalWorkflowId()).getWorkflowExecutionInfo().getStatus())
                .isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
    }

    private DescribeWorkflowExecutionResponse describe(String workflowId) {
        return testEnv.getWorkflowServiceStubs().blockingStub()
                .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(testEnv.getNamespace())
                        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId).build())
                        .build());
    }

    private static DocumentEntity document(UUID id) {
        DocumentEntity d = new DocumentEntity();
        d.setId(id);
        d.setSpaceId(SPACE_ID);
        d.setTitle("Doc audit");
        d.setBody(Map.of("type", "doc"));
        d.setStatus("brouillon");
        return d;
    }

    private static UserEntity user(UUID id, String email) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(email);
        u.setDisplayName(email);
        u.setStatus("active");
        u.setSystemAccount(false);
        return u;
    }

    private static Jwt jwtFor(UUID sub, String email) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("email", email)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    static final class RecordingActivities implements ApprovalActivities {
        final List<ApprovalStepDef> steps;
        final List<UUID> submissions = new ArrayList<>();
        final List<FinalAction> finalActions = new ArrayList<>();
        final List<String> escalations = new ArrayList<>();
        final AtomicReference<String> requestStatus = new AtomicReference<>();
        final AtomicInteger currentStep = new AtomicInteger(1);
        boolean chainExhausted;

        RecordingActivities(List<ApprovalStepDef> steps) {
            this.steps = steps;
        }

        @Override
        public List<ApprovalStepDef> loadWorkflowSteps(UUID workflowDefId) {
            return steps;
        }

        @Override
        public String recordSubmission(
                UUID documentId, UUID requesterId, UUID approvalRequestId,
                UUID workflowDefId, String temporalWorkflowId,
                int firstStepOrder, int firstStepSlaHours
        ) {
            submissions.add(approvalRequestId);
            currentStep.set(firstStepOrder);
            requestStatus.set("en_cours");
            return "en_cours";
        }

        @Override
        public String recordStepApproved(UUID approvalRequestId, int stepOrder, UUID actorId, String comment) {
            return "step_approuve";
        }

        @Override
        public String advanceToStep(UUID approvalRequestId, int newStepOrder, int newStepSlaHours) {
            currentStep.set(newStepOrder);
            return "step_" + newStepOrder;
        }

        @Override
        public String recordSlaEscalation(
                UUID approvalRequestId, int fromStepOrder, int toStepOrder,
                int toStepSlaHours, UUID systemActorId
        ) {
            escalations.add(fromStepOrder + "->" + toStepOrder);
            currentStep.set(toStepOrder);
            return "escalade_" + toStepOrder;
        }

        @Override
        public String recordChainExhausted(
                UUID documentId, UUID approvalRequestId, int lastStepOrder,
                UUID requesterId, UUID systemActorId
        ) {
            chainExhausted = true;
            requestStatus.set("en_cours");
            return "en_cours_alerte";
        }

        @Override
        public String recordFinalDecision(
                UUID documentId, UUID approvalRequestId, int stepOrder,
                String decision, UUID actorId, String comment
        ) {
            String status = "approuve".equals(decision) ? "approuve" : "rejete";
            finalActions.add(new FinalAction(approvalRequestId, decision, stepOrder));
            requestStatus.set(status);
            return status;
        }

        @Override
        public UUID ensureSystemActor() {
            return ApprovalActivitiesImpl.SYSTEM_ACTOR_ID;
        }

        record FinalAction(UUID requestId, String decision, int stepOrder) {}
    }
}
