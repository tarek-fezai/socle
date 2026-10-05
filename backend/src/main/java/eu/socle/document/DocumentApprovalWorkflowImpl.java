// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Workflow d'approbation multi-étapes avec SLA / escalade.
 *
 * <p>Une seule boucle paramétrée par {@link ApprovalStepDef} : à chaque étape,
 * {@code Workflow.await(sla, signal)} — si le timer gagne → escalade
 * ({@code reassigne}) ; si le signal gagne → approuve (étape suivante ou finale)
 * ou rejete (clôture).
 *
 * <p><b>Chaîne épuisée</b> : pas d'auto-approbation ni d'auto-rejet — voir
 * {@code docs/workflow-sla-escalade.md}.
 */
public class DocumentApprovalWorkflowImpl implements DocumentApprovalWorkflow {

    private final ApprovalActivities activities = Workflow.newActivityStub(
            ApprovalActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .build());

    private Decision pending;

    @Override
    public void decide(String decision, UUID actorId, String comment) {
        if (pending != null) {
            return;
        }
        if (!"approuve".equals(decision) && !"rejete".equals(decision)) {
            return;
        }
        pending = new Decision(decision, actorId, comment);
    }

    @Override
    public String run(UUID documentId, UUID requesterId, UUID approvalRequestId, UUID workflowDefId) {
        String workflowId = Workflow.getInfo().getWorkflowId();
        List<ApprovalStepDef> steps = new ArrayList<>(activities.loadWorkflowSteps(workflowDefId));
        if (steps.isEmpty()) {
            steps.add(new ApprovalStepDef(1, 24, null));
        }

        UUID systemActor = activities.ensureSystemActor();
        ApprovalStepDef first = steps.getFirst();
        activities.recordSubmission(
                documentId,
                requesterId,
                approvalRequestId,
                workflowDefId,
                workflowId,
                first.stepOrder(),
                first.effectiveSlaHours()
        );

        int index = 0;
        while (index >= 0 && index < steps.size()) {
            ApprovalStepDef step = steps.get(index);
            pending = null;

            Duration sla = Duration.ofHours(step.effectiveSlaHours());
            boolean decided = Workflow.await(sla, () -> pending != null);

            if (decided && pending != null) {
                if ("rejete".equals(pending.decision())) {
                    return activities.recordFinalDecision(
                            documentId,
                            approvalRequestId,
                            step.stepOrder(),
                            "rejete",
                            pending.actorId(),
                            pending.comment()
                    );
                }

                // Approuve cette étape
                boolean lastStep = index >= steps.size() - 1;
                if (lastStep) {
                    return activities.recordFinalDecision(
                            documentId,
                            approvalRequestId,
                            step.stepOrder(),
                            "approuve",
                            pending.actorId(),
                            pending.comment()
                    );
                }

                activities.recordStepApproved(
                        approvalRequestId,
                        step.stepOrder(),
                        pending.actorId(),
                        pending.comment()
                );
                int nextIndex = index + 1;
                ApprovalStepDef next = steps.get(nextIndex);
                activities.advanceToStep(
                        approvalRequestId,
                        next.stepOrder(),
                        next.effectiveSlaHours()
                );
                index = nextIndex;
                continue;
            }

            // --- Timer SLA : escalade ---
            int targetOrder = step.escalatesToStepOrder() != null
                    ? step.escalatesToStepOrder()
                    : step.stepOrder() + 1;

            int targetIndex = indexOfStep(steps, targetOrder);
            if (targetIndex < 0 || targetIndex <= index) {
                // Pas d'étape cible valide après l'étape courante → chaîne épuisée
                return activities.recordChainExhausted(
                        documentId,
                        approvalRequestId,
                        step.stepOrder(),
                        requesterId,
                        systemActor
                );
            }

            ApprovalStepDef target = steps.get(targetIndex);
            String escalationResult = activities.recordSlaEscalation(
                    approvalRequestId,
                    step.stepOrder(),
                    target.stepOrder(),
                    target.effectiveSlaHours(),
                    systemActor
            );
            // Aucun approbateur éligible sur l'étape cible (portée / quatre yeux)
            if ("en_cours_alerte".equals(escalationResult)) {
                return escalationResult;
            }
            index = targetIndex;
        }

        // Filet de sécurité : ne jamais auto-décider
        return activities.recordChainExhausted(
                documentId,
                approvalRequestId,
                steps.getLast().stepOrder(),
                requesterId,
                systemActor
        );
    }

    private static int indexOfStep(List<ApprovalStepDef> steps, int stepOrder) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).stepOrder() == stepOrder) {
                return i;
            }
        }
        return -1;
    }

    private record Decision(String decision, UUID actorId, String comment) {}
}
