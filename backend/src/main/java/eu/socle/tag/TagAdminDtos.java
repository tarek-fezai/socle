// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TagAdminDtos {

    private TagAdminDtos() {}

    public record TagAdminView(
            UUID id,
            String name,
            String color,
            long documentCount,
            /** null → afficher « — » */
            String createdByDisplayName,
            Instant createdAt,
            boolean governed
    ) {}

    public record TagAdminSummary(
            long tagCount,
            long taggedDocumentCount,
            long totalDocumentCount,
            String tagCreationPolicy
    ) {}

    public record CreateTagRequest(
            @NotBlank @Size(max = TagService.MAX_NAME_LENGTH) String name,
            String color
    ) {}

    public record RenameTagRequest(
            @NotBlank @Size(max = TagService.MAX_NAME_LENGTH) String name
    ) {}

    public record MergeTagRequest(
            @NotNull UUID targetTagId,
            /** Si true et source gouvernée : transfère les attributions vers la cible. */
            boolean transferAssignments
    ) {}

    public record TagCreationPolicyRequest(
            @NotBlank String tagCreationPolicy
    ) {}

    public record GovernedAssignmentView(
            UUID assignmentId,
            UUID roleId,
            String roleName,
            String subjectType,
            UUID subjectId,
            String subjectDisplayName
    ) {}

    public record TagListResponse(
            List<TagAdminView> tags,
            TagAdminSummary summary
    ) {}
}
