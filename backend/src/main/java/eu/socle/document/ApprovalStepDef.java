// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import java.io.Serializable;

/**
 * Étape d'un workflow d'approbation (miroir de {@code approval_workflow_steps}).
 * Doit rester sérialisable Temporal.
 */
public record ApprovalStepDef(
        int stepOrder,
        Integer slaHours,
        Integer escalatesToStepOrder
) implements Serializable {

    /** Durée SLA effective ; défaut 24h si {@code slaHours} null ou ≤ 0. */
    public int effectiveSlaHours() {
        return (slaHours == null || slaHours <= 0) ? 24 : slaHours;
    }
}
