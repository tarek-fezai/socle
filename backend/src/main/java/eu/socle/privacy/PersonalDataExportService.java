// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.privacy;

import eu.socle.attachment.AttachmentService;
import eu.socle.comment.CommentDtos.PersonalCommentExport;
import eu.socle.comment.CommentService;
import eu.socle.document.DocumentDraftService;
import eu.socle.document.DocumentDraftService.PersonalDraftExport;
import eu.socle.poll.PollDtos.PersonalPollVoteExport;
import eu.socle.poll.PollService;
import eu.socle.user.UserSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Export des données personnelles (ExportPersonalData.dc.html) — commentaires et brouillons
 * d'édition en cours ({@code document_drafts}). Étendable ultérieurement.
 *
 * <p>TODO(RGPD) : il n'existe pas encore de flux de suppression / purge de compte. Quand il sera
 * créé, il doit appeler {@link #anonymizeUserComments} et {@link #erasePersonalDrafts} (les
 * brouillons sont supprimés en cascade seulement si la ligne {@code users} est physiquement
 * supprimée ; une simple anonymisation du compte les conserverait).
 */
@Service
public class PersonalDataExportService {

    private final CommentService commentService;
    private final DocumentDraftService draftService;
    private final UserSyncService userSyncService;
    private AttachmentService attachmentService;
    private PollService pollService;

    public PersonalDataExportService(
            CommentService commentService,
            DocumentDraftService draftService,
            UserSyncService userSyncService
    ) {
        this.commentService = commentService;
        this.draftService = draftService;
        this.userSyncService = userSyncService;
    }

    @Autowired(required = false)
    void setAttachmentService(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Autowired(required = false)
    void setPollService(PollService pollService) {
        this.pollService = pollService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportFor(Jwt jwt) {
        var user = userSyncService.syncFromJwt(jwt);
        List<PersonalCommentExport> comments = commentService.exportPersonalComments(user.getId());
        List<PersonalDraftExport> drafts = draftService.exportFor(user.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId().toString());
        out.put("email", user.getEmail());
        out.put("displayName", user.getDisplayName());
        out.put("comments", comments);
        out.put("documentDrafts", drafts);
        if (attachmentService != null) {
            out.put("attachments", attachmentService.exportPersonalMetadata(user.getId()));
        }
        if (pollService != null) {
            List<PersonalPollVoteExport> votes = pollService.exportPersonalVotes(user.getId());
            out.put("pollVotes", votes);
        }
        return out;
    }

    /** Point d'entrée testable pour anonymisation (suppression de compte future). */
    @Transactional
    public int anonymizeUserComments(UUID userId) {
        return commentService.anonymizeAuthor(userId);
    }

    /** Point d'entrée pour la suppression de compte future : efface les brouillons non versionnés. */
    @Transactional
    public int erasePersonalDrafts(UUID userId) {
        return draftService.deleteAllForUser(userId);
    }
}
