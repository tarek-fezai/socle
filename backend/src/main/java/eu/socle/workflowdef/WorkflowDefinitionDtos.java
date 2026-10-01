// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.workflowdef;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public final class WorkflowDefinitionDtos {

    private WorkflowDefinitionDtos() {}

    public record StepInput(
            @NotNull Integer stepOrder,
            Integer slaHours,
            UUID approverRoleId,
            Integer escalatesToStepOrder
    ) {}

    public record UpsertRequest(
            @NotBlank String name,
            UUID scopeSpaceId,
            String scopeDocType,
            @NotBlank String status,
            @NotEmpty @Valid List<StepInput> steps
    ) {}

    public record StepView(
            UUID id,
            int stepOrder,
            Integer slaHours,
            UUID approverRoleId,
            String approverRoleName,
            Integer escalatesToStepOrder
    ) {}

    public record DefinitionView(
            UUID id,
            String name,
            UUID scopeSpaceId,
            String scopeDocType,
            String status,
            String createdAt,
            List<StepView> steps,
            /** Nombre d'instances {@code en_cours} — édition structurelle bloquée si &gt; 0. */
            int inProgressCount
    ) {}

    /**
     * Aperçu à la soumission : définition choisie + règle de précédence appliquée.
     *
     * @param matchLevel {@code space_type} | {@code space} | {@code type} | {@code global} | {@code fallback}
     */
    public record ApplicableView(
            UUID id,
            String name,
            int stepCount,
            String matchLevel,
            List<StepView> steps
    ) {}

    public record GlobalRoleView(UUID id, String name, String description) {}
}
