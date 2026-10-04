// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.customfield;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CustomFieldAdminDtos {

    private CustomFieldAdminDtos() {}

    public record FieldAdminView(
            UUID id,
            String name,
            String slug,
            String helpText,
            String fieldType,
            String scope,
            /** Nom d'espace si portée espace, sinon null. */
            String scopeSpaceName,
            boolean required,
            JsonNode options,
            String status,
            long documentCount,
            Instant createdAt
    ) {}

    public record CreateFieldRequest(
            @NotBlank @Size(max = 120) String name,
            String helpText,
            @NotBlank String fieldType,
            @NotBlank String scope,
            boolean required,
            JsonNode options,
            /** draft (défaut) ou active. */
            String status
    ) {}

    public record UpdateFieldRequest(
            @NotBlank @Size(max = 120) String name,
            String helpText,
            String fieldType,
            String scope,
            Boolean required,
            JsonNode options,
            String status,
            /** Si true : archive les options retirées au lieu de 409 field_option_in_use. */
            boolean archiveRemovedOptions
    ) {}

    public record FieldListResponse(List<FieldAdminView> fields) {}

    public record RequiredFieldRef(
            @NotNull UUID id,
            @NotBlank String name,
            @NotBlank String slug
    ) {}
}
