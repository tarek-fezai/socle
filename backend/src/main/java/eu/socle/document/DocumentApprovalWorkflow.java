// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.UUID;

/**
 * Approbation multi-étapes avec SLA / escalade (voir {@code docs/workflow-sla-escalade.md}).
 */
@WorkflowInterface
public interface DocumentApprovalWorkflow {

    @WorkflowMethod
    String run(UUID documentId, UUID requesterId, UUID approvalRequestId, UUID workflowDefId);

    /**
     * Décision de l'approbateur de l'étape courante : {@code approuve} ou {@code rejete}.
     */
    @SignalMethod
    void decide(String decision, UUID actorId, String comment);
}
