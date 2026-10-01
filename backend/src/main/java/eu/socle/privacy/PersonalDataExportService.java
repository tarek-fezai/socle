// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.privacy;

import eu.socle.comment.CommentDtos.PersonalCommentExport;
import eu.socle.comment.CommentService;
import eu.socle.user.UserSyncService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Export des données personnelles (ExportPersonalData.dc.html) — commentaires
 * de cette itération. Étendable ultérieurement.
 */
@Service
public class PersonalDataExportService {

    private final CommentService commentService;
    private final UserSyncService userSyncService;

    public PersonalDataExportService(CommentService commentService, UserSyncService userSyncService) {
        this.commentService = commentService;
        this.userSyncService = userSyncService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportFor(Jwt jwt) {
        var user = userSyncService.syncFromJwt(jwt);
        List<PersonalCommentExport> comments = commentService.exportPersonalComments(user.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.getId().toString());
        out.put("email", user.getEmail());
        out.put("displayName", user.getDisplayName());
        out.put("comments", comments);
        return out;
    }

    /** Point d'entrée testable pour anonymisation (suppression de compte future). */
    @Transactional
    public int anonymizeUserComments(UUID userId) {
        return commentService.anonymizeAuthor(userId);
    }
}
