// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.CreateDocumentRequest;
import eu.socle.document.DocumentDtos.DocumentResponse;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.space.SpaceDtos.SaveSpacePageRequest;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Page d'espace : un document ordinaire à la racine, créé au premier PUT.
 * {@code SELECT … FOR UPDATE} sérialise deux créations simultanées ;
 * {@code page_document_id} UNIQUE et l'UPDATE conditionnel empêchent d'en attacher deux.
 */
@Service
public class SpacePageService {

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final eu.socle.document.DocumentService documentService;

    public SpacePageService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            eu.socle.document.DocumentService documentService
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.documentService = documentService;
    }

    /** {@code null} si aucune page n'a encore été écrite (le contrôleur répond 204). */
    @Transactional(readOnly = true)
    public DocumentResponse get(Jwt jwt, UUID spaceId) {
        var user = userSyncService.syncFromJwt(jwt);
        SpaceRef space = requireActive(spaceId);
        authorizationService.requireSpaceRelation(user.getId(), spaceId, "viewer");
        if (space.pageDocumentId() == null) {
            return null;
        }
        return documentService.get(jwt, space.pageDocumentId());
    }

    @Transactional
    public DocumentResponse save(Jwt jwt, UUID spaceId, SaveSpacePageRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        SpaceRef space = lockActive(spaceId);
        authorizationService.requireSpaceRelation(user.getId(), spaceId, "editor");
        if (space.pageDocumentId() == null) {
            return createPage(jwt, space, request);
        }
        return documentService.update(jwt, space.pageDocumentId(), new UpdateDocumentRequest(
                space.name(), request.body(), request.changeSummary()));
    }

    private DocumentResponse createPage(Jwt jwt, SpaceRef space, SaveSpacePageRequest request) {
        return documentService.create(jwt, new CreateDocumentRequest(
                space.name(),
                request.body(),
                null,
                space.id(),
                null,
                null), documentId -> {
            int claimed = jdbc.update("""
                    UPDATE spaces
                       SET page_document_id = ?
                     WHERE id = ? AND page_document_id IS NULL
                    """,
                    documentId, space.id());
            if (claimed != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Page d'espace déjà créée");
            }
        });
    }

    private SpaceRef requireActive(UUID spaceId) {
        return load(spaceId, false);
    }

    private SpaceRef lockActive(UUID spaceId) {
        return load(spaceId, true);
    }

    private SpaceRef load(UUID spaceId, boolean forUpdate) {
        String sql = """
                SELECT id, name, page_document_id
                  FROM spaces
                 WHERE id = ? AND deleted_at IS NULL
                """ + (forUpdate ? " FOR UPDATE" : "");
        var rows = jdbc.query(sql, (rs, i) -> new SpaceRef(
                (UUID) rs.getObject("id"),
                rs.getString("name"),
                (UUID) rs.getObject("page_document_id")), spaceId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
        }
        return rows.getFirst();
    }

    private record SpaceRef(UUID id, String name, UUID pageDocumentId) {}
}
