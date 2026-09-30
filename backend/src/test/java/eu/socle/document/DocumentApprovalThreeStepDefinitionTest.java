package eu.socle.document;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bout en bout : définition à 3 étapes exercée par le moteur Temporal générique
 * (sans modifier WorkflowImpl) — au-delà du seed 1 étape.
 */
class DocumentApprovalThreeStepDefinitionTest {

    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID REQUESTER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID APPROVER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID WORKFLOW_DEF = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    TestWorkflowEnvironment testEnv;

    @AfterEach
    void tearDown() {
        if (testEnv != null) {
            testEnv.close();
        }
    }

    @Test
    void threeStepDefinition_requiresApproveOnEachStep() throws Exception {
        List<ApprovalStepDef> steps = List.of(
                new ApprovalStepDef(1, 24, 2),
                new ApprovalStepDef(2, 24, 3),
                new ApprovalStepDef(3, 24, null)
        );
        Recording acts = new Recording(steps);
        testEnv = TestWorkflowEnvironment.newInstance();
        WorkflowClient client = testEnv.getWorkflowClient();
        Worker worker = testEnv.newWorker(DocumentApprovalService.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DocumentApprovalWorkflowImpl.class);
        worker.registerActivitiesImplementations(acts);
        testEnv.start();

        UUID requestId = UUID.randomUUID();
        String workflowId = "three-step-" + requestId;
        DocumentApprovalWorkflow stub = client.newWorkflowStub(
                DocumentApprovalWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(DocumentApprovalService.TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build());

        CompletableFuture<String> result = WorkflowClient.execute(
                stub::run, DOC, REQUESTER, requestId, WORKFLOW_DEF);

        // Laisser le workflow atteindre Workflow.await (même pattern que SlaEscalationTest)
        testEnv.sleep(Duration.ofSeconds(30));
        assertThat(acts.currentStep.get()).isEqualTo(1);

        client.newWorkflowStub(DocumentApprovalWorkflow.class, workflowId)
                .decide("approuve", APPROVER, "step1");
        testEnv.sleep(Duration.ofSeconds(5));
        assertThat(acts.currentStep.get()).isEqualTo(2);
        assertThat(acts.approvedSteps).containsExactly(1);

        client.newWorkflowStub(DocumentApprovalWorkflow.class, workflowId)
                .decide("approuve", APPROVER, "step2");
        testEnv.sleep(Duration.ofSeconds(5));
        assertThat(acts.currentStep.get()).isEqualTo(3);
        assertThat(acts.approvedSteps).containsExactly(1, 2);

        client.newWorkflowStub(DocumentApprovalWorkflow.class, workflowId)
                .decide("approuve", APPROVER, "step3");

        assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("approuve");
        assertThat(acts.approvedSteps).containsExactly(1, 2);
        assertThat(acts.finalDecision.get()).isEqualTo("approuve");
        assertThat(acts.finalStepOrder.get()).isEqualTo(3);
    }

    static final class Recording implements ApprovalActivities {
        final List<ApprovalStepDef> steps;
        final List<Integer> approvedSteps = new ArrayList<>();
        final AtomicInteger currentStep = new AtomicInteger(1);
        final AtomicReference<String> finalDecision = new AtomicReference<>();
        final AtomicReference<Integer> finalStepOrder = new AtomicReference<>();

        Recording(List<ApprovalStepDef> steps) {
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
            return "en_cours";
        }

        @Override
        public String recordStepApproved(UUID approvalRequestId, int stepOrder, UUID actorId, String comment) {
            approvedSteps.add(stepOrder);
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
            currentStep.set(toStepOrder);
            return "escalade_" + toStepOrder;
        }

        @Override
        public String recordChainExhausted(
                UUID documentId, UUID approvalRequestId, int lastStepOrder,
                UUID requesterId, UUID systemActorId
        ) {
            return "en_cours_alerte";
        }

        @Override
        public String recordFinalDecision(
                UUID documentId, UUID approvalRequestId, int stepOrder,
                String decision, UUID actorId, String comment
        ) {
            finalDecision.set(decision);
            finalStepOrder.set(stepOrder);
            return "approuve".equals(decision) ? "approuve" : "rejete";
        }

        @Override
        public UUID ensureSystemActor() {
            return ApprovalActivitiesImpl.SYSTEM_ACTOR_ID;
        }
    }
}
