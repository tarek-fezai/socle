// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class CommentDtos {

    private CommentDtos() {}

    public record AnchorRequest(
            @NotBlank String exact,
            String prefix,
            String suffix,
            String blockId
    ) {}

    public record CreateCommentRequest(
            @NotBlank @Size(max = CommentBodySanitizer.MAX_LENGTH) String body,
            AnchorRequest anchor,
            UUID parentId
    ) {}

    public record UpdateCommentRequest(
            @NotBlank @Size(max = CommentBodySanitizer.MAX_LENGTH) String body
    ) {}

    public record AnchorView(
            String exact,
            String prefix,
            String suffix,
            String blockId,
            Integer versionNo,
            boolean attached,
            Integer startOffset,
            Integer endOffset
    ) {}

    public record CommentView(
            UUID id,
            UUID documentId,
            UUID parentId,
            UUID authorId,
            String authorDisplayName,
            boolean authorAnonymized,
            String body,
            String status,
            boolean deleted,
            String deletedLabel,
            AnchorView anchor,
            String createdAt,
            String updatedAt,
            UUID resolvedBy,
            String resolvedAt,
            List<CommentView> replies,
            List<MentionWarning> mentionWarnings
    ) {}

    public record MentionWarning(UUID userId, String displayName, String message) {}

    public record MentionSuggestion(UUID userId, String displayName, String email) {}

    public record CommentsPage(
            UUID documentId,
            int versionNo,
            List<CommentView> threads,
            List<CommentView> detached,
            int openThreadCount
    ) {}

    public record PersonalCommentExport(
            UUID commentId,
            UUID documentId,
            String body,
            String status,
            String createdAt,
            boolean deleted
    ) {}
}
