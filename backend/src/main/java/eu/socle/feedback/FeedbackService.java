// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.feedback;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentRepository;
import eu.socle.feedback.FeedbackDtos.FeedbackTotals;
import eu.socle.feedback.FeedbackDtos.FeedbackView;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Retour « cette page vous a-t-elle été utile ? ». Un vote par (document, utilisateur).
 * Les totaux ne sont visibles que des éditeurs du document ; le vote d'autrui n'est jamais exposé
 * (voir {@code docs/privacy.md}).
 */
@Service
public class FeedbackService {

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final Clock clock;

    public FeedbackService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            DocumentRepository documentRepository,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.documentRepository = documentRepository;
        this.clock = clock;
    }

    @Transactional
    public FeedbackView put(Jwt jwt, UUID documentId, boolean helpful) {
        var user = userSyncService.syncFromJwt(jwt);
        requireViewable(user.getId(), documentId);

        jdbc.update("""
                INSERT INTO document_feedback (document_id, user_id, helpful, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (document_id, user_id) DO UPDATE
                  SET helpful = excluded.helpful,
                      updated_at = excluded.updated_at
                """,
                documentId, user.getId(), helpful, Timestamp.from(clock.instant()));
        return view(user.getId(), documentId);
    }

    @Transactional(readOnly = true)
    public FeedbackView get(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireViewable(user.getId(), documentId);
        return view(user.getId(), documentId);
    }

    private FeedbackView view(UUID userId, UUID documentId) {
        Boolean myVote = jdbc.query(
                "SELECT helpful FROM document_feedback WHERE document_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getBoolean("helpful") : null,
                documentId, userId);

        FeedbackTotals totals = null;
        if (authorizationService.hasRelation(userId, "document", documentId, "editor")) {
            totals = jdbc.query("""
                    SELECT count(*) FILTER (WHERE helpful) AS yes,
                           count(*) FILTER (WHERE NOT helpful) AS no
                      FROM document_feedback
                     WHERE document_id = ?
                    """,
                    rs -> {
                        rs.next();
                        return new FeedbackTotals(rs.getLong("yes"), rs.getLong("no"));
                    },
                    documentId);
        }
        return new FeedbackView(myVote, totals);
    }

    private void requireViewable(UUID userId, UUID documentId) {
        documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        if (!authorizationService.hasRelation(userId, "document", documentId, "viewer")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
    }
}
