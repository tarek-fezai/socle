// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.comment;

import eu.socle.comment.CommentDtos.CommentView;
import eu.socle.comment.CommentDtos.CommentsPage;
import eu.socle.comment.CommentDtos.CreateCommentRequest;
import eu.socle.comment.CommentDtos.UpdateCommentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/documents/{documentId}/comments")
    public CommentsPage list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID documentId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer version
    ) {
        return commentService.list(jwt, documentId, status, version);
    }

    @GetMapping("/documents/{documentId}/comments/mention-suggestions")
    public List<CommentDtos.MentionSuggestion> mentionSuggestions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "") String q
    ) {
        return commentService.suggestMentions(jwt, documentId, q);
    }

    @PostMapping("/documents/{documentId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID documentId,
            @Valid @RequestBody CreateCommentRequest request
    ) {
        return commentService.create(jwt, documentId, request);
    }

    @PatchMapping("/comments/{id}")
    public CommentView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCommentRequest request
    ) {
        return commentService.update(jwt, id, request);
    }

    @DeleteMapping("/comments/{id}")
    public CommentView delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return commentService.delete(jwt, id);
    }

    @PostMapping("/comments/{id}/resolve")
    public CommentView resolve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return commentService.resolve(jwt, id);
    }

    @PostMapping("/comments/{id}/reopen")
    public CommentView reopen(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        return commentService.reopen(jwt, id);
    }
}
