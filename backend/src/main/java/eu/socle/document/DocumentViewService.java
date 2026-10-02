// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Compteurs de vues agrégés (jour × document) — aucun user_id stocké.
 */
@Service
public class DocumentViewService {

    /** Rétention des agrégats quotidiens (écran Analytics futur). */
    static final int RETENTION_MONTHS = 13;

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final DocumentRepository documentRepository;
    private final Clock clock;

    public DocumentViewService(
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

    /**
     * Incrémente le compteur du jour si l'utilisateur peut voir le document.
     * Sinon 404 (existence non révélée) sans incrément.
     */
    @Transactional
    public void recordView(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));
        if (!authorizationService.hasRelation(user.getId(), "document", documentId, "viewer")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }

        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO document_view_counts (document_id, day, count)
                VALUES (?, ?, 1)
                ON CONFLICT (document_id, day) DO UPDATE
                  SET count = document_view_counts.count + 1
                """,
                documentId, java.sql.Date.valueOf(day));
    }

    @Transactional
    public int purgeOlderThanRetention() {
        LocalDate cutoff = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusMonths(RETENTION_MONTHS);
        return jdbc.update(
                "DELETE FROM document_view_counts WHERE day < ?",
                java.sql.Date.valueOf(cutoff));
    }
}
