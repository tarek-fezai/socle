// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.trash;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.attachment.AttachmentService;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.retention.LegalHoldService;
import eu.socle.storage.DocumentHistoryPurgeService;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import eu.socle.user.UserSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Soft-delete réel : {@code deleted_at} sur documents/folders/spaces.
 * {@code trash_items} = index Corbeille (affichage) — une ligne par ressource affectée.
 * Cascade applicative (pas ON DELETE CASCADE PostgreSQL, réservé à la purge réelle).
 */
@Service
public class TrashService {

    private static final Logger log = LoggerFactory.getLogger(TrashService.class);

    public static final Duration RETENTION = Duration.ofDays(30);

    private final JdbcTemplate jdbcTemplate;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private AttachmentService attachmentService;
    private LegalHoldService legalHoldService;
    private DocumentHistoryPurgeService historyPurgeService;

    @Autowired
    public TrashService(
            JdbcTemplate jdbcTemplate,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this(jdbcTemplate, userSyncService, authorizationService, auditService, objectMapper, Clock.systemUTC());
    }

    /** Visible pour tests (horloge fixe). */
    TrashService(
            JdbcTemplate jdbcTemplate,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Autowired(required = false)
    void setAttachmentService(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Autowired(required = false)
    void setLegalHoldService(LegalHoldService legalHoldService) {
        this.legalHoldService = legalHoldService;
    }

    @Autowired(required = false)
    void setHistoryPurgeService(DocumentHistoryPurgeService historyPurgeService) {
        this.historyPurgeService = historyPurgeService;
    }

    private static final int MAX_LIST_LIMIT = 200;

    @Transactional(readOnly = true)
    public TrashPage list(Jwt jwt, String resourceType, int offset, int limit) {
        var user = userSyncService.syncFromJwt(jwt);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        int safeOffset = Math.max(offset, 0);

        List<TrashItemView> all;
        boolean filterType = resourceType != null && !resourceType.isBlank();
        if (!filterType) {
            all = jdbcTemplate.query("""
                    SELECT t.id, t.resource_type, t.resource_id, t.resource_snapshot::text AS snapshot,
                           t.deleted_by, t.deleted_at, t.purge_at,
                           u.display_name AS deleted_by_name
                      FROM trash_items t
                      LEFT JOIN users u ON u.id = t.deleted_by
                     ORDER BY t.deleted_at DESC
                    """,
                    (rs, i) -> toTrashItemView(rs));
        } else {
            all = jdbcTemplate.query("""
                    SELECT t.id, t.resource_type, t.resource_id, t.resource_snapshot::text AS snapshot,
                           t.deleted_by, t.deleted_at, t.purge_at,
                           u.display_name AS deleted_by_name
                      FROM trash_items t
                      LEFT JOIN users u ON u.id = t.deleted_by
                     WHERE t.resource_type = ?
                     ORDER BY t.deleted_at DESC
                    """,
                    (rs, i) -> toTrashItemView(rs),
                    resourceType.trim().toLowerCase());
        }

        List<TrashItemView> visible = all.stream()
                .filter(item -> canView(user.getId(), item.resourceType(), item.resourceId()))
                .toList();
        int total = visible.size();
        List<TrashItemView> page = visible.stream()
                .skip(safeOffset)
                .limit(safeLimit)
                .toList();
        return new TrashPage(page, safeOffset, safeLimit, total);
    }

    /**
     * Prévisualisation Corbeille : ressource soft-delete accessible par id trash.
     */
    @Transactional(readOnly = true)
    public TrashPreview preview(Jwt jwt, UUID trashItemId) {
        var user = userSyncService.syncFromJwt(jwt);
        TrashRow item = requireTrashItem(trashItemId);
        requireEdit(user.getId(), item.resourceType(), item.resourceId());
        return switch (item.resourceType()) {
            case "document" -> previewDocument(item);
            case "folder" -> previewFolder(item);
            case "space" -> previewSpace(item);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type inconnu");
        };
    }

    @Transactional
    public SoftDeleteResult softDeleteDocument(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");
        DocRow doc = requireActiveDocument(documentId);
        if (legalHoldService != null) {
            legalHoldService.assertDocumentNotHeld(documentId);
        }
        Instant now = clock.instant();
        softDeleteDocumentRow(doc, user.getId(), now);
        auditService.record(user.getId(), false, AuditActions.DOCUMENT_TRASHED,
                "document", documentId, Map.of("title", doc.title()), null);
        return new SoftDeleteResult(1, 0, 0);
    }

    @Transactional
    public SoftDeleteResult softDeleteFolder(Jwt jwt, UUID folderId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireFolderRelation(user.getId(), folderId, "editor");
        FolderRow folder = requireActiveFolder(folderId);
        List<UUID> folderIds = new ArrayList<>(descendantFolderIds(folderId));
        folderIds.add(folderId);

        List<DocRow> docsToTrash = new ArrayList<>();
        for (UUID fid : folderIds) {
            if (!fid.equals(folderId)) {
                authorizationService.requireFolderRelation(user.getId(), fid, "editor");
            }
            docsToTrash.addAll(activeDocumentsInFolder(fid));
        }
        for (DocRow d : docsToTrash) {
            authorizationService.requireDocumentRelation(user.getId(), d.id(), "editor");
        }
        if (legalHoldService != null) {
            legalHoldService.assertDocumentsNotHeld(docsToTrash.stream().map(DocRow::id).toList());
        }

        Instant now = clock.instant();
        for (DocRow d : docsToTrash) {
            softDeleteDocumentRow(d, user.getId(), now);
        }
        List<FolderRow> folders = folderIds.stream()
                .map(this::requireActiveFolderAllowAlready)
                .filter(f -> f != null && f.deletedAt() == null)
                .toList();
        for (FolderRow f : folders) {
            softDeleteFolderRow(f, user.getId(), now);
        }
        auditService.record(user.getId(), false, AuditActions.FOLDER_DELETED,
                "folder", folderId,
                Map.of(
                        "title", folder.name(),
                        "name", folder.name(),
                        "cascadedDocuments", docsToTrash.size(),
                        "cascadedFolders", folders.size()),
                null);
        return new SoftDeleteResult(docsToTrash.size(), folders.size(), 0);
    }

    @Transactional
    public SoftDeleteResult softDeleteSpace(Jwt jwt, UUID spaceId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireSpaceRelation(user.getId(), spaceId, "editor");
        SpaceRow space = requireActiveSpace(spaceId);
        List<DocRow> docsToTrash = activeDocumentsInSpace(spaceId);
        List<FolderRow> folders = activeFoldersInSpace(spaceId);

        for (FolderRow f : folders) {
            authorizationService.requireFolderRelation(user.getId(), f.id(), "editor");
        }
        for (DocRow d : docsToTrash) {
            authorizationService.requireDocumentRelation(user.getId(), d.id(), "editor");
        }
        if (legalHoldService != null) {
            legalHoldService.assertSpaceNotHeld(spaceId);
        }

        Instant now = clock.instant();
        for (DocRow d : docsToTrash) {
            softDeleteDocumentRow(d, user.getId(), now);
        }
        for (FolderRow f : folders) {
            softDeleteFolderRow(f, user.getId(), now);
        }
        softDeleteSpaceRow(space, user.getId(), now);
        auditService.record(user.getId(), false, AuditActions.SPACE_TRASHED,
                "space", spaceId,
                Map.of(
                        "name", space.name(),
                        "cascadedDocuments", docsToTrash.size(),
                        "cascadedFolders", folders.size()),
                null);
        return new SoftDeleteResult(docsToTrash.size(), folders.size(), 1);
    }

    @Transactional
    public RestoreResult restore(Jwt jwt, UUID trashItemId) {
        var user = userSyncService.syncFromJwt(jwt);
        TrashRow item = requireTrashItem(trashItemId);
        requireEdit(user.getId(), item.resourceType(), item.resourceId());

        List<UUID> restoredDocs = new ArrayList<>();
        List<UUID> restoredFolders = new ArrayList<>();
        List<UUID> restoredSpaces = new ArrayList<>();
        String message = null;

        switch (item.resourceType()) {
            case "document" -> {
                message = reparentDocumentToRootIfParentTrashed(item.resourceId());
                restoreDocument(item.resourceId());
                restoredDocs.add(item.resourceId());
            }
            case "folder" -> {
                message = reparentFolderToRootIfParentTrashed(item.resourceId());
                List<UUID> folderIds = descendantFolderIdsIncludingSelf(item.resourceId());
                List<UUID> docIds = new ArrayList<>();
                for (UUID fid : folderIds) {
                    docIds.addAll(softDeletedDocumentsInFolder(fid));
                }
                for (UUID fid : folderIds) {
                    requireEdit(user.getId(), "folder", fid);
                }
                for (UUID docId : docIds) {
                    requireEdit(user.getId(), "document", docId);
                }
                for (UUID docId : docIds) {
                    restoreDocument(docId);
                    restoredDocs.add(docId);
                }
                for (UUID fid : folderIds) {
                    if (restoreFolder(fid)) {
                        restoredFolders.add(fid);
                    }
                }
            }
            case "space" -> {
                List<UUID> docIds = softDeletedDocumentsInSpace(item.resourceId());
                List<UUID> folderIds = softDeletedFoldersInSpace(item.resourceId());
                requireEdit(user.getId(), "space", item.resourceId());
                for (UUID fid : folderIds) {
                    requireEdit(user.getId(), "folder", fid);
                }
                for (UUID docId : docIds) {
                    requireEdit(user.getId(), "document", docId);
                }
                for (UUID docId : docIds) {
                    restoreDocument(docId);
                    restoredDocs.add(docId);
                }
                for (UUID fid : folderIds) {
                    if (restoreFolder(fid)) {
                        restoredFolders.add(fid);
                    }
                }
                if (restoreSpace(item.resourceId())) {
                    restoredSpaces.add(item.resourceId());
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type inconnu");
        }

        removeTrashItems("document", restoredDocs);
        removeTrashItems("folder", restoredFolders);
        removeTrashItems("space", restoredSpaces);

        String restoreAction = switch (item.resourceType()) {
            case "document" -> AuditActions.DOCUMENT_RESTORED_FROM_TRASH;
            case "folder" -> AuditActions.FOLDER_RESTORED;
            case "space" -> AuditActions.SPACE_RESTORED_FROM_TRASH;
            default -> AuditActions.DOCUMENT_RESTORED_FROM_TRASH;
        };
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("trashItemId", trashItemId.toString());
        meta.put("restoredDocuments", restoredDocs.size());
        meta.put("restoredFolders", restoredFolders.size());
        meta.put("restoredSpaces", restoredSpaces.size());
        if (message != null) {
            meta.put("message", message);
        }
        auditService.record(user.getId(), false, restoreAction,
                item.resourceType(), item.resourceId(), meta, null);

        return new RestoreResult(restoredDocs.size(), restoredFolders.size(), restoredSpaces.size(), message);
    }

    /**
     * Purge anticipée (bouton « Supprimer définitivement ») — DELETE SQL réel.
     */
    @Transactional
    public void purgeNow(Jwt jwt, UUID trashItemId) {
        var user = userSyncService.syncFromJwt(jwt);
        TrashRow item = requireTrashItem(trashItemId);
        requireEdit(user.getId(), item.resourceType(), item.resourceId());
        assertResourceNotHeld(item.resourceType(), item.resourceId());
        hardDeleteResource(item.resourceType(), item.resourceId(), user.getId());
        jdbcTemplate.update("DELETE FROM trash_items WHERE id = ?", trashItemId);
        cleanupOrphanTrashItems();
        auditService.record(user.getId(), false, purgedAction(item.resourceType()),
                item.resourceType(), item.resourceId(),
                Map.of("trashItemId", trashItemId.toString(), "mode", "manual"),
                null);
    }

    /**
     * Job : hard-delete des ressources dont {@code trash_items.purge_at <= now()}.
     */
    @Transactional
    public int purgeExpired() {
        Instant now = clock.instant();
        List<TrashRow> due = jdbcTemplate.query("""
                SELECT id, resource_type, resource_id, resource_snapshot::text AS snapshot,
                       deleted_by, deleted_at, purge_at
                  FROM trash_items
                 WHERE purge_at <= ?
                 ORDER BY CASE resource_type
                            WHEN 'document' THEN 1
                            WHEN 'folder' THEN 2
                            WHEN 'space' THEN 3
                          END,
                          deleted_at ASC
                """,
                (rs, i) -> mapTrashRow(rs),
                Timestamp.from(now));
        int count = 0;
        for (TrashRow item : due) {
            try {
                if (isResourceHeld(item.resourceType(), item.resourceId())) {
                    // Legal hold : l'élément reste en corbeille (réessayé après levée du gel).
                    log.info("Purge corbeille ignorée (legal hold actif) type={} resource={}",
                            item.resourceType(), item.resourceId());
                    continue;
                }
                hardDeleteResource(item.resourceType(), item.resourceId(), null);
                jdbcTemplate.update("DELETE FROM trash_items WHERE id = ?", item.id());
                auditService.record(null, true, purgedAction(item.resourceType()),
                        item.resourceType(), item.resourceId(),
                        Map.of("trashItemId", item.id().toString(), "mode", "scheduled"),
                        null);
                count++;
            } catch (Exception e) {
                log.error("Échec purge trash_item={} type={} resource={}",
                        item.id(), item.resourceType(), item.resourceId(), e);
            }
        }
        cleanupOrphanTrashItems();
        return count;
    }

    private static String purgedAction(String resourceType) {
        return switch (resourceType) {
            case "folder" -> AuditActions.FOLDER_PURGED;
            case "space" -> AuditActions.SPACE_PURGED;
            default -> AuditActions.DOCUMENT_PURGED;
        };
    }

    /**
     * Si le dossier parent est encore en corbeille, rattache le document à la racine de l'espace.
     * @return message utilisateur ou null
     */
    private String reparentDocumentToRootIfParentTrashed(UUID documentId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT d.folder_id, d.space_id, f.deleted_at AS folder_deleted
                  FROM documents d
                  LEFT JOIN folders f ON f.id = d.folder_id
                 WHERE d.id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("folderId", rs.getObject("folder_id"));
                    m.put("spaceId", rs.getObject("space_id"));
                    m.put("folderDeleted", rs.getTimestamp("folder_deleted") != null);
                    return m;
                },
                documentId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        if (row.get("folderId") == null || !Boolean.TRUE.equals(row.get("folderDeleted"))) {
            return null;
        }
        jdbcTemplate.update("""
                UPDATE documents SET folder_id = NULL WHERE id = ?
                """, documentId);
        return "Le dossier parent est encore en corbeille — document restauré à la racine de l'espace";
    }

    /** Même politique pour un dossier dont le parent_folder est encore soft-delete. */
    private String reparentFolderToRootIfParentTrashed(UUID folderId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT f.parent_folder_id, f.space_id, p.deleted_at AS parent_deleted
                  FROM folders f
                  LEFT JOIN folders p ON p.id = f.parent_folder_id
                 WHERE f.id = ?
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("parentId", rs.getObject("parent_folder_id"));
                    m.put("spaceId", rs.getObject("space_id"));
                    m.put("parentDeleted", rs.getTimestamp("parent_deleted") != null);
                    return m;
                },
                folderId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        if (row.get("parentId") == null || !Boolean.TRUE.equals(row.get("parentDeleted"))) {
            return null;
        }
        jdbcTemplate.update("""
                UPDATE folders SET parent_folder_id = NULL WHERE id = ?
                """, folderId);
        // OpenFGA : remonter parent/inherit_from vers l'espace
        UUID spaceId = (UUID) row.get("spaceId");
        UUID oldParent = (UUID) row.get("parentId");
        if (spaceId != null && oldParent != null) {
            authorizationService.reparentFolder(folderId, "folder", oldParent, "space", spaceId);
        }
        return "Le dossier parent est encore en corbeille — dossier restauré à la racine de l'espace";
    }

    // ── soft-delete helpers ──────────────────────────────────────────

    private void softDeleteDocumentRow(DocRow doc, UUID deletedBy, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE documents
                   SET deleted_at = ?, deleted_by = ?
                 WHERE id = ? AND deleted_at IS NULL
                """,
                Timestamp.from(now), deletedBy, doc.id());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document déjà dans la corbeille");
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("title", doc.title());
        snap.put("spaceId", doc.spaceId().toString());
        snap.put("folderId", doc.folderId() != null ? doc.folderId().toString() : null);
        snap.put("status", doc.status());
        insertTrashItem("document", doc.id(), snap, deletedBy, now);
    }

    private void softDeleteFolderRow(FolderRow folder, UUID deletedBy, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE folders
                   SET deleted_at = ?, deleted_by = ?
                 WHERE id = ? AND deleted_at IS NULL
                """,
                Timestamp.from(now), deletedBy, folder.id());
        if (updated == 0) {
            return;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("title", folder.name());
        snap.put("name", folder.name());
        snap.put("spaceId", folder.spaceId().toString());
        snap.put("parentFolderId", folder.parentFolderId() != null ? folder.parentFolderId().toString() : null);
        insertTrashItem("folder", folder.id(), snap, deletedBy, now);
    }

    private void softDeleteSpaceRow(SpaceRow space, UUID deletedBy, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE spaces
                   SET deleted_at = ?, deleted_by = ?
                 WHERE id = ? AND deleted_at IS NULL
                """,
                Timestamp.from(now), deletedBy, space.id());
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Espace déjà dans la corbeille");
        }
        insertTrashItem("space", space.id(), Map.of(
                "name", space.name(),
                "color", space.color() != null ? space.color() : ""
        ), deletedBy, now);
    }

    private void insertTrashItem(
            String resourceType,
            UUID resourceId,
            Map<String, Object> snapshot,
            UUID deletedBy,
            Instant deletedAt
    ) {
        Instant purgeAt = deletedAt.plus(RETENTION);
        jdbcTemplate.update("""
                INSERT INTO trash_items
                  (id, resource_type, resource_id, resource_snapshot, deleted_by, deleted_at, purge_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                ON CONFLICT (resource_type, resource_id) DO UPDATE
                  SET resource_snapshot = EXCLUDED.resource_snapshot,
                      deleted_by = EXCLUDED.deleted_by,
                      deleted_at = EXCLUDED.deleted_at,
                      purge_at = EXCLUDED.purge_at
                """,
                UUID.randomUUID(),
                resourceType,
                resourceId,
                toJson(snapshot),
                deletedBy,
                Timestamp.from(deletedAt),
                Timestamp.from(purgeAt));
    }

    // ── restore helpers ──────────────────────────────────────────────

    private void restoreDocument(UUID documentId) {
        jdbcTemplate.update("""
                UPDATE documents SET deleted_at = NULL, deleted_by = NULL
                 WHERE id = ? AND deleted_at IS NOT NULL
                """, documentId);
    }

    private boolean restoreFolder(UUID folderId) {
        return jdbcTemplate.update("""
                UPDATE folders SET deleted_at = NULL, deleted_by = NULL
                 WHERE id = ? AND deleted_at IS NOT NULL
                """, folderId) > 0;
    }

    private boolean restoreSpace(UUID spaceId) {
        return jdbcTemplate.update("""
                UPDATE spaces SET deleted_at = NULL, deleted_by = NULL
                 WHERE id = ? AND deleted_at IS NOT NULL
                """, spaceId) > 0;
    }

    private void removeTrashItems(String type, List<UUID> ids) {
        for (UUID id : ids) {
            jdbcTemplate.update(
                    "DELETE FROM trash_items WHERE resource_type = ? AND resource_id = ?",
                    type, id);
        }
    }

    // ── hard delete / purge ──────────────────────────────────────────

    private void hardDeleteResource(String type, UUID id, UUID actorId) {
        switch (type) {
            case "document" -> hardDeleteDocument(id, actorId);
            case "folder" -> {
                // Children soft-deleted first (docs then subfolders) to avoid FK surprises
                for (UUID child : descendantFolderIds(id)) {
                    for (UUID docId : softDeletedDocumentsInFolder(child)) {
                        hardDeleteDocument(docId, actorId);
                        jdbcTemplate.update(
                                "DELETE FROM trash_items WHERE resource_type = 'document' AND resource_id = ?",
                                docId);
                    }
                    jdbcTemplate.update("DELETE FROM folders WHERE id = ? AND deleted_at IS NOT NULL", child);
                    jdbcTemplate.update(
                            "DELETE FROM trash_items WHERE resource_type = 'folder' AND resource_id = ?",
                            child);
                }
                for (UUID docId : softDeletedDocumentsInFolder(id)) {
                    hardDeleteDocument(docId, actorId);
                    jdbcTemplate.update(
                            "DELETE FROM trash_items WHERE resource_type = 'document' AND resource_id = ?",
                            docId);
                }
                jdbcTemplate.update("DELETE FROM folders WHERE id = ? AND deleted_at IS NOT NULL", id);
            }
            case "space" -> {
                for (UUID docId : softDeletedDocumentsInSpace(id)) {
                    hardDeleteDocument(docId, actorId);
                    jdbcTemplate.update(
                            "DELETE FROM trash_items WHERE resource_type = 'document' AND resource_id = ?",
                            docId);
                }
                for (UUID fid : softDeletedFoldersInSpace(id)) {
                    jdbcTemplate.update("DELETE FROM folders WHERE id = ? AND deleted_at IS NOT NULL", fid);
                    jdbcTemplate.update(
                            "DELETE FROM trash_items WHERE resource_type = 'folder' AND resource_id = ?",
                            fid);
                }
                jdbcTemplate.update("DELETE FROM spaces WHERE id = ? AND deleted_at IS NOT NULL", id);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type inconnu");
        }
    }

    private void hardDeleteDocument(UUID documentId, UUID actorId) {
        // Garde-fou même si l'appelant a déjà vérifié (défense en profondeur) : 409 legal_hold_active.
        if (legalHoldService != null) {
            legalHoldService.assertDocumentNotHeld(documentId);
        }
        if (attachmentService != null) {
            attachmentService.purgeForDocument(documentId, actorId, actorId == null);
        } else {
            jdbcTemplate.update("DELETE FROM attachments WHERE document_id = ?", documentId);
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM documents WHERE id = ? AND deleted_at IS NOT NULL", documentId);
        if (deleted > 0 && historyPurgeService != null) {
            // Après commit : réécriture de l'historique Git (documents/{uuid}.md retiré de toutes les révisions).
            historyPurgeService.purgeAfterCommit(actorId, documentId);
        }
    }

    // ── legal hold ───────────────────────────────────────────────────

    /** 409 {@code legal_hold_active} si un gel couvre la ressource (document, dossier ou espace). */
    private void assertResourceNotHeld(String type, UUID id) {
        if (legalHoldService == null) {
            return;
        }
        switch (type) {
            case "document" -> legalHoldService.assertDocumentNotHeld(id);
            case "folder" -> legalHoldService.assertDocumentsNotHeld(allDocumentIdsInFolderTree(id));
            case "space" -> legalHoldService.assertSpaceNotHeld(id);
            default -> { }
        }
    }

    private boolean isResourceHeld(String type, UUID id) {
        if (legalHoldService == null) {
            return false;
        }
        try {
            assertResourceNotHeld(type, id);
            return false;
        } catch (CodedStatusException e) {
            if (ApiErrors.LEGAL_HOLD_ACTIVE.equals(e.getCode())) {
                return true;
            }
            throw e;
        }
    }

    /** Tous les documents (actifs ou en corbeille) du dossier et de ses sous-dossiers. */
    private List<UUID> allDocumentIdsInFolderTree(UUID folderId) {
        return jdbcTemplate.query("""
                WITH RECURSIVE tree AS (
                  SELECT id FROM folders WHERE id = ?
                  UNION ALL
                  SELECT f.id FROM folders f JOIN tree t ON f.parent_folder_id = t.id
                )
                SELECT d.id FROM documents d WHERE d.folder_id IN (SELECT id FROM tree)
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                folderId);
    }

    private void cleanupOrphanTrashItems() {
        jdbcTemplate.update("""
                DELETE FROM trash_items t
                 WHERE (t.resource_type = 'document' AND NOT EXISTS (
                         SELECT 1 FROM documents d WHERE d.id = t.resource_id))
                    OR (t.resource_type = 'folder' AND NOT EXISTS (
                         SELECT 1 FROM folders f WHERE f.id = t.resource_id))
                    OR (t.resource_type = 'space' AND NOT EXISTS (
                         SELECT 1 FROM spaces s WHERE s.id = t.resource_id))
                """);
    }

    // ── queries ──────────────────────────────────────────────────────

    private List<UUID> descendantFolderIds(UUID rootFolderId) {
        return jdbcTemplate.query("""
                WITH RECURSIVE tree AS (
                  SELECT id FROM folders
                   WHERE parent_folder_id = ? AND deleted_at IS NULL
                  UNION ALL
                  SELECT f.id FROM folders f
                    JOIN tree t ON f.parent_folder_id = t.id
                   WHERE f.deleted_at IS NULL
                )
                SELECT id FROM tree
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                rootFolderId);
    }

    private List<UUID> descendantFolderIdsIncludingSelf(UUID rootFolderId) {
        List<UUID> ids = new ArrayList<>();
        ids.add(rootFolderId);
        ids.addAll(jdbcTemplate.query("""
                WITH RECURSIVE tree AS (
                  SELECT id FROM folders WHERE parent_folder_id = ?
                  UNION ALL
                  SELECT f.id FROM folders f JOIN tree t ON f.parent_folder_id = t.id
                )
                SELECT id FROM tree
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                rootFolderId));
        return ids;
    }

    private List<DocRow> activeDocumentsInFolder(UUID folderId) {
        return jdbcTemplate.query("""
                SELECT id, space_id, folder_id, title, status
                  FROM documents
                 WHERE folder_id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> mapDoc(rs), folderId);
    }

    private List<DocRow> activeDocumentsInSpace(UUID spaceId) {
        return jdbcTemplate.query("""
                SELECT id, space_id, folder_id, title, status
                  FROM documents
                 WHERE space_id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> mapDoc(rs), spaceId);
    }

    private List<FolderRow> activeFoldersInSpace(UUID spaceId) {
        return jdbcTemplate.query("""
                SELECT id, space_id, parent_folder_id, name, deleted_at
                  FROM folders
                 WHERE space_id = ? AND deleted_at IS NULL
                 ORDER BY parent_folder_id NULLS FIRST
                """,
                (rs, i) -> mapFolder(rs), spaceId);
    }

    private List<UUID> softDeletedDocumentsInFolder(UUID folderId) {
        return jdbcTemplate.query("""
                SELECT id FROM documents WHERE folder_id = ? AND deleted_at IS NOT NULL
                """,
                (rs, i) -> (UUID) rs.getObject("id"), folderId);
    }

    private List<UUID> softDeletedDocumentsInSpace(UUID spaceId) {
        return jdbcTemplate.query("""
                SELECT id FROM documents WHERE space_id = ? AND deleted_at IS NOT NULL
                """,
                (rs, i) -> (UUID) rs.getObject("id"), spaceId);
    }

    private List<UUID> softDeletedFoldersInSpace(UUID spaceId) {
        return jdbcTemplate.query("""
                SELECT id FROM folders WHERE space_id = ? AND deleted_at IS NOT NULL
                """,
                (rs, i) -> (UUID) rs.getObject("id"), spaceId);
    }

    private DocRow requireActiveDocument(UUID id) {
        List<DocRow> rows = jdbcTemplate.query("""
                SELECT id, space_id, folder_id, title, status
                  FROM documents WHERE id = ?
                """,
                (rs, i) -> mapDoc(rs), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
        // Check deleted via separate query
        Boolean deleted = jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM documents WHERE id = ?", Boolean.class, id);
        if (Boolean.TRUE.equals(deleted)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document déjà dans la corbeille");
        }
        return rows.getFirst();
    }

    private FolderRow requireActiveFolder(UUID id) {
        FolderRow f = requireActiveFolderAllowAlready(id);
        if (f == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier introuvable");
        }
        if (f.deletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dossier déjà dans la corbeille");
        }
        return f;
    }

    private FolderRow requireActiveFolderAllowAlready(UUID id) {
        List<FolderRow> rows = jdbcTemplate.query("""
                SELECT id, space_id, parent_folder_id, name, deleted_at
                  FROM folders WHERE id = ?
                """,
                (rs, i) -> mapFolder(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private SpaceRow requireActiveSpace(UUID id) {
        List<SpaceRow> rows = jdbcTemplate.query("""
                SELECT id, name, color, deleted_at FROM spaces WHERE id = ?
                """,
                (rs, i) -> new SpaceRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        rs.getString("color"),
                        rs.getTimestamp("deleted_at") != null
                                ? rs.getTimestamp("deleted_at").toInstant() : null
                ), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
        }
        if (rows.getFirst().deletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Espace déjà dans la corbeille");
        }
        return rows.getFirst();
    }

    private TrashRow requireTrashItem(UUID id) {
        List<TrashRow> rows = jdbcTemplate.query("""
                SELECT id, resource_type, resource_id, resource_snapshot::text AS snapshot,
                       deleted_by, deleted_at, purge_at
                  FROM trash_items WHERE id = ?
                """,
                (rs, i) -> mapTrashRow(rs), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément de corbeille introuvable");
        }
        return rows.getFirst();
    }

    private TrashPreview previewDocument(TrashRow item) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, title, status, body::text AS body, deleted_at
                  FROM documents WHERE id = ? AND deleted_at IS NOT NULL
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("title", rs.getString("title"));
                    m.put("status", rs.getString("status"));
                    m.put("body", parseJson(rs.getString("body")));
                    m.put("deletedAt", rs.getTimestamp("deleted_at").toInstant().toString());
                    return m;
                },
                item.resourceId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document soft-delete introuvable");
        }
        return new TrashPreview(item.id(), "document", item.resourceId(), rows.getFirst());
    }

    private TrashPreview previewFolder(TrashRow item) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, name, space_id, deleted_at
                  FROM folders WHERE id = ? AND deleted_at IS NOT NULL
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("title", rs.getString("name"));
                    m.put("name", rs.getString("name"));
                    m.put("spaceId", rs.getObject("space_id"));
                    m.put("deletedAt", rs.getTimestamp("deleted_at").toInstant().toString());
                    return m;
                },
                item.resourceId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier soft-delete introuvable");
        }
        return new TrashPreview(item.id(), "folder", item.resourceId(), rows.getFirst());
    }

    private TrashPreview previewSpace(TrashRow item) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, name, color, deleted_at
                  FROM spaces WHERE id = ? AND deleted_at IS NOT NULL
                """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id"));
                    m.put("name", rs.getString("name"));
                    m.put("color", rs.getString("color"));
                    m.put("deletedAt", rs.getTimestamp("deleted_at").toInstant().toString());
                    return m;
                },
                item.resourceId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace soft-delete introuvable");
        }
        return new TrashPreview(item.id(), "space", item.resourceId(), rows.getFirst());
    }

    private void requireEdit(UUID userId, String type, UUID resourceId) {
        if (!canEdit(userId, type, resourceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès corbeille refusé");
        }
    }

    /** Lecture : ce que l'utilisateur verrait si la ressource n'était pas soft-delete. */
    private boolean canView(UUID userId, String type, UUID resourceId) {
        return authorizationService.hasRelation(userId, type, resourceId, "viewer")
                || canEdit(userId, type, resourceId);
    }

    private boolean canEdit(UUID userId, String type, UUID resourceId) {
        return authorizationService.hasRelation(userId, type, resourceId, "editor")
                || authorizationService.hasRelation(userId, type, resourceId, "owner");
    }

    private TrashItemView toTrashItemView(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> snapshot = parseJson(rs.getString("snapshot"));
        String title = snapshotTitle(rs.getString("resource_type"), snapshot);
        return new TrashItemView(
                (UUID) rs.getObject("id"),
                rs.getString("resource_type"),
                (UUID) rs.getObject("resource_id"),
                title,
                snapshot,
                (UUID) rs.getObject("deleted_by"),
                rs.getString("deleted_by_name"),
                rs.getTimestamp("deleted_at").toInstant(),
                rs.getTimestamp("purge_at").toInstant()
        );
    }

    private static String snapshotTitle(String resourceType, Map<String, Object> snapshot) {
        if (snapshot == null) {
            return "";
        }
        Object t = snapshot.get("title");
        if (t == null) {
            t = snapshot.get("name");
        }
        return t != null ? t.toString() : "";
    }

    private static DocRow mapDoc(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("space_id"),
                (UUID) rs.getObject("folder_id"),
                rs.getString("title"),
                rs.getString("status")
        );
    }

    private static FolderRow mapFolder(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp del = rs.getTimestamp("deleted_at");
        return new FolderRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("space_id"),
                (UUID) rs.getObject("parent_folder_id"),
                rs.getString("name"),
                del != null ? del.toInstant() : null
        );
    }

    private static TrashRow mapTrashRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TrashRow(
                (UUID) rs.getObject("id"),
                rs.getString("resource_type"),
                (UUID) rs.getObject("resource_id"),
                rs.getString("snapshot"),
                (UUID) rs.getObject("deleted_by"),
                rs.getTimestamp("deleted_at").toInstant(),
                rs.getTimestamp("purge_at").toInstant()
        );
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public record SoftDeleteResult(int documents, int folders, int spaces) {}

    public record RestoreResult(int documents, int folders, int spaces, String message) {
        public RestoreResult(int documents, int folders, int spaces) {
            this(documents, folders, spaces, null);
        }
    }

    public record TrashPage(List<TrashItemView> items, int offset, int limit, long total) {}

    public record TrashItemView(
            UUID id,
            String resourceType,
            UUID resourceId,
            String title,
            Map<String, Object> snapshot,
            UUID deletedBy,
            String deletedByName,
            Instant deletedAt,
            Instant purgeAt
    ) {}

    public record TrashPreview(
            UUID trashItemId,
            String resourceType,
            UUID resourceId,
            Map<String, Object> resource
    ) {}

    private record DocRow(UUID id, UUID spaceId, UUID folderId, String title, String status) {}
    private record FolderRow(UUID id, UUID spaceId, UUID parentFolderId, String name, Instant deletedAt) {}
    private record SpaceRow(UUID id, String name, String color, Instant deletedAt) {}
    private record TrashRow(
            UUID id, String resourceType, UUID resourceId, String snapshot,
            UUID deletedBy, Instant deletedAt, Instant purgeAt
    ) {}
}
