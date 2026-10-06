// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.export;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.TransclusionResolver;
import eu.socle.export.TipTapPdfRenderer.NamedBody;
import eu.socle.storage.DocumentStore;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Export PDF documentaire — réutilise {@link TransclusionResolver} et
 * {@link AuthorizationService#listViewableDocumentIds} borné au périmètre
 * (folder / tag), avec Check viewer final.
 * Voir {@code docs/export.md}.
 */
@Service
public class ExportService {

    private final DocumentRepository documentRepository;
    private final DocumentStore documentStore;
    private final TransclusionResolver transclusionResolver;
    private final AuthorizationService authorizationService;
    private final UserSyncService userSyncService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public ExportService(
            DocumentRepository documentRepository,
            DocumentStore documentStore,
            TransclusionResolver transclusionResolver,
            AuthorizationService authorizationService,
            UserSyncService userSyncService,
            AuditService auditService,
            JdbcTemplate jdbc
    ) {
        this.documentRepository = documentRepository;
        this.documentStore = documentStore;
        this.transclusionResolver = transclusionResolver;
        this.authorizationService = authorizationService;
        this.userSyncService = userSyncService;
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ExportFile exportDocument(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");

        DocumentEntity doc = documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));

        Map<String, Object> raw = documentStore.readCurrentContent(doc.getId(), doc.getBody());
        Map<String, Object> resolved = transclusionResolver.resolve(user.getId(), doc.getId(), raw);
        byte[] pdf = TipTapPdfRenderer.renderDocument(doc.getTitle(), resolved);

        Map<String, Object> meta = new HashMap<>();
        meta.put("format", "pdf");
        meta.put("title", doc.getTitle());
        meta.put("spaceId", doc.getSpaceId().toString());
        auditService.record(
                user.getId(), false, AuditActions.DOCUMENT_EXPORTED,
                "document", documentId, meta, null);

        return new ExportFile(safeFilename(doc.getTitle()) + ".pdf", "application/pdf", pdf);
    }

    @Transactional(readOnly = true)
    public ExportFile exportFolder(Jwt jwt, UUID folderId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireFolderRelation(user.getId(), folderId, "viewer");

        FolderRow folder = requireActiveFolder(folderId);
        List<UUID> viewableIds = authorizationService.listViewableDocumentIds(
                user.getId(), DocumentScope.folder(folderId));
        List<DocumentEntity> included = documentRepository.findAllActiveByIdIn(viewableIds).stream()
                .sorted((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()))
                .toList();

        List<NamedBody> pages = resolveAll(user.getId(), included);
        String title = "Dossier — " + folder.name();
        byte[] pdf = TipTapPdfRenderer.renderCollection(title, pages);

        Map<String, Object> meta = new HashMap<>();
        meta.put("format", "pdf");
        meta.put("title", folder.name());
        meta.put("documentCount", pages.size());
        meta.put("candidateCount", viewableIds.size());
        auditService.record(
                user.getId(), false, AuditActions.FOLDER_EXPORTED,
                "folder", folderId, meta, null);

        return new ExportFile(safeFilename(folder.name()) + ".pdf", "application/pdf", pdf);
    }

    @Transactional(readOnly = true)
    public ExportFile exportTag(Jwt jwt, UUID tagId) {
        var user = userSyncService.syncFromJwt(jwt);
        TagRow tag = requireTag(tagId);

        List<UUID> viewableIds = authorizationService.listViewableDocumentIds(
                user.getId(), DocumentScope.tag(tagId));
        List<DocumentEntity> included = documentRepository.findAllActiveByIdIn(viewableIds).stream()
                .sorted((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()))
                .toList();

        List<NamedBody> pages = resolveAll(user.getId(), included);
        String title = "Tag — " + tag.name();
        byte[] pdf = TipTapPdfRenderer.renderCollection(title, pages);

        Map<String, Object> meta = new HashMap<>();
        meta.put("format", "pdf");
        meta.put("tag", tag.name());
        meta.put("documentCount", pages.size());
        meta.put("candidateCount", viewableIds.size());
        auditService.record(
                user.getId(), false, AuditActions.TAG_EXPORTED,
                "tag", tagId, meta, null);

        return new ExportFile(safeFilename(tag.name()) + ".pdf", "application/pdf", pdf);
    }

    private List<NamedBody> resolveAll(UUID userId, List<DocumentEntity> docs) {
        List<NamedBody> pages = new ArrayList<>();
        for (DocumentEntity d : docs) {
            Map<String, Object> raw = documentStore.readCurrentContent(d.getId(), d.getBody());
            Map<String, Object> resolved = transclusionResolver.resolve(userId, d.getId(), raw);
            pages.add(new NamedBody(d.getTitle(), resolved));
        }
        return pages;
    }

    private FolderRow requireActiveFolder(UUID folderId) {
        List<FolderRow> rows = jdbc.query("""
                SELECT id, name FROM folders WHERE id = ? AND deleted_at IS NULL
                """, (rs, i) -> new FolderRow(
                (UUID) rs.getObject("id"),
                rs.getString("name")), folderId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier introuvable");
        }
        return rows.getFirst();
    }

    private TagRow requireTag(UUID tagId) {
        List<TagRow> rows = jdbc.query("""
                SELECT id, name FROM tags WHERE id = ?
                """, (rs, i) -> new TagRow(
                (UUID) rs.getObject("id"),
                rs.getString("name")), tagId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag introuvable");
        }
        return rows.getFirst();
    }

    private static String safeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "export";
        }
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "export" : cleaned;
    }

    public record ExportFile(String filename, String contentType, byte[] bytes) {}

    private record FolderRow(UUID id, String name) {}

    private record TagRow(UUID id, String name) {}
}
