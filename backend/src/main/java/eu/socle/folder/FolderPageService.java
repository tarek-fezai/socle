// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import eu.socle.document.DocumentDtos.CreateDocumentRequest;
import eu.socle.document.DocumentDtos.DocumentResponse;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.document.DocumentService;
import eu.socle.folder.FolderDtos.SaveFolderPageRequest;
import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Page de dossier : un document ordinaire, créé au premier PUT.
 * {@code SELECT … FOR UPDATE} sérialise deux créations simultanées ;
 * {@code page_document_id} UNIQUE empêche d'attacher deux pages, et le perdant
 * réutilise le document gagnant.
 */
@Service
public class FolderPageService {

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final DocumentService documentService;

    public FolderPageService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            DocumentService documentService
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.documentService = documentService;
    }

    /** {@code null} si aucune page n'a encore été écrite (le contrôleur répond 204). */
    @Transactional(readOnly = true)
    public DocumentResponse get(Jwt jwt, UUID folderId) {
        var user = userSyncService.syncFromJwt(jwt);
        FolderRef folder = requireActive(folderId);
        authorizationService.requireFolderRelation(user.getId(), folderId, "viewer");
        if (folder.pageDocumentId() == null) {
            return null;
        }
        return documentService.get(jwt, folder.pageDocumentId());
    }

    @Transactional
    public DocumentResponse save(Jwt jwt, UUID folderId, SaveFolderPageRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        FolderRef folder = lockActive(folderId);
        authorizationService.requireFolderRelation(user.getId(), folderId, "editor");
        if (folder.pageDocumentId() == null) {
            return createPage(jwt, folder, request);
        }
        return documentService.update(jwt, folder.pageDocumentId(), new UpdateDocumentRequest(
                folder.name(), request.body(), request.changeSummary()));
    }

    private DocumentResponse createPage(Jwt jwt, FolderRef folder, SaveFolderPageRequest request) {
        return documentService.create(jwt, new CreateDocumentRequest(
                folder.name(),
                request.body(),
                null,
                folder.spaceId(),
                null,
                folder.id()), documentId -> {
            int claimed = jdbc.update("""
                    UPDATE folders
                       SET page_document_id = ?
                     WHERE id = ? AND page_document_id IS NULL
                    """,
                    documentId, folder.id());
            if (claimed != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Page de dossier déjà créée");
            }
        });
    }

    private FolderRef requireActive(UUID folderId) {
        return load(folderId, false);
    }

    private FolderRef lockActive(UUID folderId) {
        return load(folderId, true);
    }

    private FolderRef load(UUID folderId, boolean forUpdate) {
        String sql = """
                SELECT id, space_id, name, page_document_id
                  FROM folders
                 WHERE id = ? AND deleted_at IS NULL
                """ + (forUpdate ? " FOR UPDATE" : "");
        var rows = jdbc.query(sql, (rs, i) -> new FolderRef(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("space_id"),
                rs.getString("name"),
                (UUID) rs.getObject("page_document_id")), folderId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier introuvable");
        }
        return rows.getFirst();
    }

    private record FolderRef(UUID id, UUID spaceId, String name, UUID pageDocumentId) {}
}
