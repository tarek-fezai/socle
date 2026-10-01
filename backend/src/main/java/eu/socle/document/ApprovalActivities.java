// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.UUID;

@ActivityInterface
public interface ApprovalActivities {

    /** Charge les étapes ordonnées depuis {@code approval_workflow_steps}. */
    @ActivityMethod
    List<ApprovalStepDef> loadWorkflowSteps(UUID workflowDefId);

    /**
     * Insert {@code approval_requests} (step 1 + {@code sla_deadline_at}) et passe le document
     * en {@code en_revue}. Seul point d'écriture initial.
     */
    @ActivityMethod
    String recordSubmission(
            UUID documentId,
            UUID requesterId,
            UUID approvalRequestId,
            UUID workflowDefId,
            String temporalWorkflowId,
            int firstStepOrder,
            int firstStepSlaHours
    );

    /** Approbation d'une étape intermédiaire (chaîne continue). */
    @ActivityMethod
    String recordStepApproved(
            UUID approvalRequestId,
            int stepOrder,
            UUID actorId,
            String comment
    );

    /** Passe à une nouvelle étape (après approbation d'étape ou escalade). */
    @ActivityMethod
    String advanceToStep(
            UUID approvalRequestId,
            int newStepOrder,
            int newStepSlaHours
    );

    /**
     * Escalade automatique SLA : {@code approval_actions.decision = reassigne},
     * avance {@code current_step_order} + recalcule {@code sla_deadline_at}.
     */
    @ActivityMethod
    String recordSlaEscalation(
            UUID approvalRequestId,
            int fromStepOrder,
            int toStepOrder,
            int toStepSlaHours,
            UUID systemActorId
    );

    /**
     * Chaîne SLA épuisée : pas d'auto-approbation / auto-rejet.
     * Reste {@code en_cours}, alerte notification, action {@code reassigne} informative.
     */
    @ActivityMethod
    String recordChainExhausted(
            UUID documentId,
            UUID approvalRequestId,
            int lastStepOrder,
            UUID requesterId,
            UUID systemActorId
    );

    /** Décision finale ({@code approuve} | {@code rejete}) + clôture document. */
    @ActivityMethod
    String recordFinalDecision(
            UUID documentId,
            UUID approvalRequestId,
            int stepOrder,
            String decision,
            UUID actorId,
            String comment
    );

    /** Utilisateur système pour actions automatiques (escalade SLA). */
    @ActivityMethod
    UUID ensureSystemActor();
}
