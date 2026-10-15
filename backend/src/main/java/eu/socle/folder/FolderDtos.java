// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class FolderDtos {

    private FolderDtos() {}

    public record CreateFolderRequest(
            @NotNull UUID spaceId,
            UUID parentFolderId,
            @NotBlank String name,
            Integer position
    ) {}

    public record UpdateFolderRequest(String name, Integer position) {}

    public record MoveFolderRequest(UUID parentFolderId, Integer position) {}

    public record MoveDocumentRequest(UUID folderId, Integer position) {}

    /**
     * Utilisateur ou système. {@code kind} vaut {@code user} ou {@code system}.
     * {@code id} est null pour le système.
     */
    public record ActorRef(String kind, UUID id, String displayName) {}

    public record OwnerGroupRef(UUID id, String name) {}

    /** Documents directs du dossier, page exclue. */
    public record StatusCounts(int validated, int inReview, int draft) {}

    public record FolderView(
            UUID id,
            UUID spaceId,
            UUID parentFolderId,
            String name,
            int position,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            int documentCount,
            int folderCount,
            UUID pageDocumentId,
            /** {@code actif} ou {@code archivé}. */
            String status,
            ActorRef creator,
            ActorRef lastModifiedBy,
            Instant lastModifiedAt,
            OwnerGroupRef ownerGroup,
            /** Moyenne des documents directs, page exclue. Null s'il n'y en a aucun. */
            BigDecimal reliabilityAverage,
            StatusCounts statusCounts
    ) {
        public FolderView(
                UUID id,
                UUID spaceId,
                UUID parentFolderId,
                String name,
                int position,
                UUID createdBy,
                Instant createdAt,
                Instant updatedAt,
                int documentCount,
                int folderCount
        ) {
            this(
                    id, spaceId, parentFolderId, name, position, createdBy, createdAt, updatedAt,
                    documentCount, folderCount,
                    null, "actif",
                    new ActorRef(createdBy == null ? "system" : "user", createdBy, null),
                    new ActorRef(createdBy == null ? "system" : "user", createdBy, null),
                    updatedAt, null, null, new StatusCounts(0, 0, 0));
        }
    }

    /** Corps TipTap de la page. Le titre reste le nom du dossier. */
    public record SaveFolderPageRequest(
            @NotNull Map<String, Object> body,
            String changeSummary
    ) {}

    public record TreeDocumentNode(
            UUID id,
            String title,
            UUID folderId,
            int position,
            String status
    ) {}

    public record TreeFolderNode(
            UUID id,
            String name,
            UUID parentFolderId,
            int position,
            int documentCount,
            int folderCount,
            List<TreeFolderNode> folders,
            List<TreeDocumentNode> documents
    ) {}

    public record SpaceTreeResponse(
            UUID spaceId,
            String spaceName,
            List<TreeFolderNode> folders,
            List<TreeDocumentNode> documents,
            int totalFolders,
            int totalDocuments
    ) {}

    public record RestoreMessage(String message) {}
}
