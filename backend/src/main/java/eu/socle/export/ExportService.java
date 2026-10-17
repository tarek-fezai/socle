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
import eu.socle.storage.TipTapMarkdown;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    /**
     * Archive du dossier. La page est {@code _dossier.md} à la racine, pas un document frère.
     */
    @Transactional(readOnly = true)
    public ExportFile exportFolderZip(Jwt jwt, UUID folderId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireFolderRelation(user.getId(), folderId, "viewer");
        FolderRow folder = requireActiveFolder(folderId);

        List<UUID> viewableIds = authorizationService.listViewableDocumentIds(
                user.getId(), DocumentScope.folder(folderId));
        Set<UUID> pageIds = new HashSet<>(jdbc.query(
                "SELECT page_document_id FROM folders WHERE id = ? AND page_document_id IS NOT NULL",
                (rs, i) -> (UUID) rs.getObject(1),
                folderId));
        List<DocumentEntity> included = documentRepository.findAllActiveByIdIn(viewableIds).stream()
                .filter(d -> !pageIds.contains(d.getId()))
                .sorted((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()))
                .toList();

        byte[] zip = zipDocuments(user.getId(), pageIds, "_dossier.md", included);
        Map<String, Object> meta = new HashMap<>();
        meta.put("format", "zip");
        meta.put("title", folder.name());
        meta.put("documentCount", included.size());
        meta.put("folderPage", !pageIds.isEmpty());
        auditService.record(
                user.getId(), false, AuditActions.FOLDER_EXPORTED,
                "folder", folderId, meta, null);
        return new ExportFile(safeFilename(folder.name()) + ".zip", "application/zip", zip);
    }

    /**
     * Archive de l'espace. La page est {@code _espace.md} à la racine, pas un document frère.
     */
    @Transactional(readOnly = true)
    public ExportFile exportSpaceZip(Jwt jwt, UUID spaceId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireSpaceRelation(user.getId(), spaceId, "viewer");
        String name = jdbc.query(
                "SELECT name FROM spaces WHERE id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? rs.getString(1) : null,
                spaceId);
        if (name == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
        }
        Set<UUID> pageIds = new HashSet<>(jdbc.query(
                "SELECT page_document_id FROM spaces WHERE id = ? AND page_document_id IS NOT NULL",
                (rs, i) -> (UUID) rs.getObject(1),
                spaceId));
        List<UUID> viewableIds = authorizationService.listViewableDocumentIds(
                user.getId(), DocumentScope.space(spaceId));
        List<DocumentEntity> included = documentRepository.findAllActiveByIdIn(viewableIds).stream()
                .filter(d -> !pageIds.contains(d.getId()))
                .sorted((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()))
                .toList();
        byte[] zip = zipDocuments(user.getId(), pageIds, "_espace.md", included);
        Map<String, Object> meta = new HashMap<>();
        meta.put("format", "zip");
        meta.put("title", name);
        meta.put("documentCount", included.size());
        meta.put("spacePage", !pageIds.isEmpty());
        auditService.record(
                user.getId(), false, AuditActions.SPACE_EXPORTED,
                "space", spaceId, meta, null);
        return new ExportFile(safeFilename(name) + ".zip", "application/zip", zip);
    }

    private byte[] zipDocuments(
            UUID userId, Set<UUID> pageIds, String pageEntry, List<DocumentEntity> documents
    ) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (UUID pageId : pageIds) {
                documentRepository.findActiveById(pageId).ifPresent(page ->
                        writeMarkdown(zip, userId, page, pageEntry));
            }
            Set<String> used = new HashSet<>();
            used.add(pageEntry);
            for (DocumentEntity doc : documents) {
                String name = uniqueName(safeFilename(doc.getTitle()) + ".md", used);
                writeMarkdown(zip, userId, doc, name);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Export ZIP impossible");
        }
        return buffer.toByteArray();
    }

    private void writeMarkdown(ZipOutputStream zip, UUID userId, DocumentEntity doc, String entryName) {
        try {
            Map<String, Object> raw = documentStore.readCurrentContent(doc.getId(), doc.getBody());
            Map<String, Object> resolved = transclusionResolver.resolve(userId, doc.getId(), raw);
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(TipTapMarkdown.toMarkdown(resolved).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Export ZIP impossible");
        }
    }

    private static String uniqueName(String name, Set<String> used) {
        String candidate = name;
        int n = 2;
        while (!used.add(candidate)) {
            int dot = name.lastIndexOf('.');
            candidate = dot < 0 ? name + "-" + n : name.substring(0, dot) + "-" + n + name.substring(dot);
            n++;
        }
        return candidate;
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
