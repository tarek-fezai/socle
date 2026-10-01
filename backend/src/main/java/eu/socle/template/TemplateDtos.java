// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.template;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TemplateDtos {

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_SPACE = "space";

    private TemplateDtos() {}

    /** Entrée de liste — sans corps. */
    public record TemplateSummary(
            UUID id,
            String name,
            String description,
            String docType,
            /** global | space */
            String scope,
            /** null pour un modèle global */
            UUID spaceId,
            List<UUID> defaultTagIds,
            int version,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt,
            /** L'appelant peut modifier / supprimer ce modèle. */
            boolean canManage
    ) {}

    /** Détail — corps TipTap inclus (toujours lu depuis PostgreSQL). */
    public record TemplateResponse(
            UUID id,
            String name,
            String description,
            String docType,
            String scope,
            UUID spaceId,
            List<UUID> defaultTagIds,
            int version,
            UUID createdBy,
            UUID updatedBy,
            Instant createdAt,
            Instant updatedAt,
            boolean canManage,
            Map<String, Object> body
    ) {}

    /**
     * @param spaceId null = modèle global (administrateur système) ; sinon modèle d'espace (owner)
     */
    public record CreateTemplateRequest(
            @NotBlank String name,
            String description,
            String docType,
            List<UUID> defaultTagIds,
            Map<String, Object> body,
            UUID spaceId
    ) {}

    /** PATCH — champs absents (null) inchangés ; description/docType vides → effacés. */
    public record UpdateTemplateRequest(
            String name,
            String description,
            String docType,
            List<UUID> defaultTagIds,
            Map<String, Object> body
    ) {}

    /** Avertissement non bloquant à la création d'une page depuis un modèle. */
    public record CreationWarning(
            String code,
            String message,
            /** Cibles de transclusion concernées (ids déjà présents dans le corps du modèle). */
            List<UUID> targetDocumentIds
    ) {}

    public record CreationWarningsResponse(
            UUID templateId,
            UUID spaceId,
            List<CreationWarning> warnings
    ) {}

    /**
     * @param scope {@code global} | {@code space}
     * @param spaceId requis si {@code scope=space}
     */
    public record SaveAsTemplateRequest(
            @NotBlank String scope,
            UUID spaceId,
            @NotBlank String name,
            String description
    ) {}

    public record SaveAsTemplateResponse(
            TemplateResponse template,
            List<String> warnings
    ) {}
}
