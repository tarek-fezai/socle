// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA / escalade multi-étapes — horloge Temporal accélérée (pas de vrai sleep heures).
 * Voir docs/workflow-sla-escalade.md.
 */
class DocumentApprovalSlaEscalationTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID REQUESTER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID APPROVER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID WORKFLOW_DEF = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    TestWorkflowEnvironment testEnv;
    WorkflowClient client;

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    private SlaRecordingActivities startEnv(List<ApprovalStepDef> steps) {
        if (testEnv != null) {
            testEnv.close();
        }
        SlaRecordingActivities activities = new SlaRecordingActivities(steps);
        testEnv = TestWorkflowEnvironment.newInstance();
        client = testEnv.getWorkflowClient();
        Worker worker = testEnv.newWorker(DocumentApprovalService.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DocumentApprovalWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        testEnv.start();
        return activities;
    }

    @Test
    void slaTimeout_escalatesToStep2_thenApprove() throws Exception {
        SlaRecordingActivities activities = startEnv(List.of(
                new ApprovalStepDef(1, 1, 2),
                new ApprovalStepDef(2, 1, null)
        ));

        UUID requestId = UUID.randomUUID();
        String workflowId = "sla-esc-" + requestId;
        DocumentApprovalWorkflow stub = client.newWorkflowStub(
                DocumentApprovalWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(DocumentApprovalService.TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build());

        CompletableFuture<String> resultFuture = WorkflowClient.execute(
                stub::run, DOC, REQUESTER, requestId, WORKFLOW_DEF);

        testEnv.sleep(Duration.ofMinutes(5));
        assertThat(activities.currentStep.get()).isEqualTo(1);

        // Dépasser SLA étape 1 → escalade
        testEnv.sleep(Duration.ofHours(1));
        assertThat(activities.escalations).containsExactly("1->2");
        assertThat(activities.reassigneActions).hasSize(1);
        assertThat(activities.currentStep.get()).isEqualTo(2);
        assertThat(activities.requestStatus.get()).isEqualTo("en_cours");
        // Miroir du contrat AuditService (ApprovalActivitiesImpl.recordSlaEscalation)
        assertThat(activities.auditEvents).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("approval.escalated");
            assertThat(e.actorIsSystem()).isTrue();
            assertThat(e.actorId()).isNull();
            assertThat(e.metadata()).containsEntry("fromStepOrder", 1).containsEntry("toStepOrder", 2);
        });

        client.newWorkflowStub(DocumentApprovalWorkflow.class, workflowId)
                .decide("approuve", APPROVER, "ok après escalade");

        String status = resultFuture.get(10, TimeUnit.SECONDS);
        assertThat(status).isEqualTo("approuve");
        assertThat(activities.finalDecision.get()).isEqualTo("approuve");
        assertThat(activities.finalStepOrder.get()).isEqualTo(2);
        assertThat(activities.auditEvents).extracting(AuditEvent::action)
                .contains("document.submitted_for_approval", "approval.escalated", "document.approved");
    }

    @Test
    void decideBeforeSla_noEscalation() throws Exception {
        SlaRecordingActivities activities = startEnv(List.of(
                new ApprovalStepDef(1, 24, null)
        ));

        UUID requestId = UUID.randomUUID();
        String workflowId = "sla-ontime-" + requestId;
        DocumentApprovalWorkflow stub = client.newWorkflowStub(
                DocumentApprovalWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(DocumentApprovalService.TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build());

        CompletableFuture<String> resultFuture = WorkflowClient.execute(
                stub::run, DOC, REQUESTER, requestId, WORKFLOW_DEF);

        testEnv.sleep(Duration.ofSeconds(30));
        client.newWorkflowStub(DocumentApprovalWorkflow.class, workflowId)
                .decide("approuve", APPROVER, "avant échéance");

        String status = resultFuture.get(10, TimeUnit.SECONDS);
        assertThat(status).isEqualTo("approuve");
        assertThat(activities.escalations).isEmpty();
        assertThat(activities.reassigneActions).isEmpty();
    }

    @Test
    void chainExhausted_staysEnCours_notifiesRequesterAndLastApprover_andAudits() throws Exception {
        SlaRecordingActivities activities = startEnv(List.of(
                new ApprovalStepDef(1, 1, null)
        ));
        activities.lastApproverId = APPROVER;

        UUID requestId = UUID.randomUUID();
        String workflowId = "sla-exhaust-" + requestId;
        DocumentApprovalWorkflow stub = client.newWorkflowStub(
                DocumentApprovalWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(DocumentApprovalService.TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build());

        CompletableFuture<String> resultFuture = WorkflowClient.execute(
                stub::run, DOC, REQUESTER, requestId, WORKFLOW_DEF);

        testEnv.sleep(Duration.ofHours(2));

        String status = resultFuture.get(10, TimeUnit.SECONDS);
        assertThat(status).isEqualTo("en_cours_alerte");
        assertThat(activities.chainExhausted).isTrue();
        assertThat(activities.requestStatus.get()).isEqualTo("en_cours");
        assertThat(activities.finalDecision.get()).isNull();
        assertThat(activities.reassigneActions).isNotEmpty();

        // notifications + audit (contrat ApprovalActivitiesImpl)
        assertThat(activities.notificationRecipients)
                .containsExactlyInAnyOrder(REQUESTER, APPROVER);
        assertThat(activities.notificationTypes)
                .containsOnly("approval_chain_exhausted");
        assertThat(activities.auditEvents).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("approval.chain_exhausted");
            assertThat(e.actorIsSystem()).isTrue();
            assertThat(e.actorId()).isNull();
            assertThat(e.metadata()).containsEntry("stepsTraversed", 1);
        });
    }

    /** Contrat d'événement d'audit produit par les activities (miroir ApprovalActivitiesImpl). */
    record AuditEvent(UUID actorId, boolean actorIsSystem, String action, Map<String, Object> metadata) {}

    static final class SlaRecordingActivities implements ApprovalActivities {
        final List<ApprovalStepDef> steps;
        final List<String> escalations = new ArrayList<>();
        final List<String> reassigneActions = new ArrayList<>();
        final List<AuditEvent> auditEvents = new ArrayList<>();
        final List<UUID> notificationRecipients = new ArrayList<>();
        final List<String> notificationTypes = new ArrayList<>();
        final AtomicReference<String> requestStatus = new AtomicReference<>();
        final AtomicInteger currentStep = new AtomicInteger(1);
        final AtomicReference<String> finalDecision = new AtomicReference<>();
        final AtomicInteger finalStepOrder = new AtomicInteger();
        boolean chainExhausted;
        UUID lastApproverId = APPROVER;

        SlaRecordingActivities(List<ApprovalStepDef> steps) {
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
            currentStep.set(firstStepOrder);
            requestStatus.set("en_cours");
            auditEvents.add(new AuditEvent(
                    requesterId, false, "document.submitted_for_approval",
                    Map.of("stepOrder", firstStepOrder)));
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
            reassigneActions.add(fromStepOrder + " → " + toStepOrder);
            currentStep.set(toStepOrder);
            auditEvents.add(new AuditEvent(
                    null, true, "approval.escalated",
                    Map.of("fromStepOrder", fromStepOrder, "toStepOrder", toStepOrder)));
            return "escalade_" + toStepOrder;
        }

        @Override
        public String recordChainExhausted(
                UUID documentId, UUID approvalRequestId, int lastStepOrder,
                UUID requesterId, UUID systemActorId
        ) {
            chainExhausted = true;
            reassigneActions.add("épuisé@" + lastStepOrder);
            requestStatus.set("en_cours");
            // Miroir ApprovalActivitiesImpl : demandeur + dernier approbateur
            notificationRecipients.add(requesterId);
            notificationTypes.add("approval_chain_exhausted");
            if (lastApproverId != null && !lastApproverId.equals(requesterId)) {
                notificationRecipients.add(lastApproverId);
                notificationTypes.add("approval_chain_exhausted");
            }
            auditEvents.add(new AuditEvent(
                    null, true, "approval.chain_exhausted",
                    Map.of("stepsTraversed", lastStepOrder, "lastStepOrder", lastStepOrder)));
            return "en_cours_alerte";
        }

        @Override
        public String recordFinalDecision(
                UUID documentId, UUID approvalRequestId, int stepOrder,
                String decision, UUID actorId, String comment
        ) {
            String status = "approuve".equals(decision) ? "approuve" : "rejete";
            finalDecision.set(decision);
            finalStepOrder.set(stepOrder);
            requestStatus.set(status);
            auditEvents.add(new AuditEvent(
                    actorId, false,
                    "approuve".equals(decision) ? "document.approved" : "document.rejected",
                    Map.of("stepOrder", stepOrder, "decision", decision)));
            return status;
        }

        @Override
        public UUID ensureSystemActor() {
            return ApprovalActivitiesImpl.SYSTEM_ACTOR_ID;
        }
    }
}
