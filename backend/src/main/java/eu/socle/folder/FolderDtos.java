// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.folder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
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
            int folderCount
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
