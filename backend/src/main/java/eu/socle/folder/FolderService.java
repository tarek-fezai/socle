// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.folder;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.config.SocleProperties;
import eu.socle.folder.FolderDtos.CreateFolderRequest;
import eu.socle.folder.FolderDtos.FolderView;
import eu.socle.folder.FolderDtos.MoveDocumentRequest;
import eu.socle.folder.FolderDtos.MoveFolderRequest;
import eu.socle.folder.FolderDtos.SpaceTreeResponse;
import eu.socle.folder.FolderDtos.TreeDocumentNode;
import eu.socle.folder.FolderDtos.TreeFolderNode;
import eu.socle.folder.FolderDtos.UpdateFolderRequest;
import eu.socle.trash.TrashService;
import eu.socle.user.UserSyncService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class FolderService {

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final TrashService trashService;
    private final int maxDepth;

    public FolderService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            TrashService trashService,
            SocleProperties properties
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.trashService = trashService;
        this.maxDepth = properties.folders() != null
                ? properties.folders().effectiveMaxDepth()
                : 5;
    }

    @Transactional(readOnly = true)
    public FolderView get(Jwt jwt, UUID folderId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireFolderRelation(user.getId(), folderId, "viewer");
        FolderRow row = requireActiveFolder(folderId);
        Counts counts = countsForFolder(user.getId(), row);
        return toView(row, counts.documents(), counts.folders());
    }

    @Transactional
    public FolderView create(Jwt jwt, CreateFolderRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        UUID spaceId = request.spaceId();
        requireActiveSpace(spaceId);
        UUID parentId = request.parentFolderId();
        requireManageInParent(user.getId(), spaceId, parentId);

        int depth = 1;
        if (parentId != null) {
            FolderRow parent = requireActiveFolder(parentId);
            if (!parent.spaceId().equals(spaceId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Le dossier parent n'appartient pas à cet espace");
            }
            depth = folderDepth(parentId) + 1;
        }
        if (depth > maxDepth) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Profondeur maximale de dossiers atteinte (" + maxDepth + ")");
        }

        String name = normalizeName(request.name());
        int position = request.position() != null
                ? request.position()
                : nextFolderPosition(spaceId, parentId);

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO folders
                      (id, space_id, parent_folder_id, name, position, created_by, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'active')
                    """,
                    id, spaceId, parentId, name, position, user.getId());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un dossier portant ce nom existe déjà au même niveau");
        }

        String parentType = parentId != null ? "folder" : "space";
        UUID parentObjId = parentId != null ? parentId : spaceId;
        authorizationService.provisionFolderAccess(id, parentType, parentObjId);

        auditService.record(user.getId(), false, AuditActions.FOLDER_CREATED,
                "folder", id,
                Map.of("name", name, "spaceId", spaceId.toString(),
                        "parentFolderId", parentId != null ? parentId.toString() : ""),
                null);

        FolderRow row = requireActiveFolder(id);
        return toView(row, 0, 0);
    }

    @Transactional
    public FolderView update(Jwt jwt, UUID folderId, UpdateFolderRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        FolderRow row = requireActiveFolder(folderId);
        requireManageInParent(user.getId(), row.spaceId(), row.parentFolderId());

        String newName = request.name() != null ? normalizeName(request.name()) : row.name();
        int newPos = request.position() != null ? request.position() : row.position();
        boolean renamed = !newName.equals(row.name());

        try {
            int updated = jdbc.update("""
                    UPDATE folders
                       SET name = ?, position = ?, updated_at = now()
                     WHERE id = ? AND deleted_at IS NULL
                    """,
                    newName, newPos, folderId);
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier introuvable");
            }
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un dossier portant ce nom existe déjà au même niveau");
        }

        if (renamed) {
            auditService.record(user.getId(), false, AuditActions.FOLDER_RENAMED,
                    "folder", folderId,
                    Map.of("from", row.name(), "to", newName),
                    null);
        }

        FolderRow updated = requireActiveFolder(folderId);
        Counts counts = countsForFolder(user.getId(), updated);
        return toView(updated, counts.documents(), counts.folders());
    }

    @Transactional
    public FolderView move(Jwt jwt, UUID folderId, MoveFolderRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        FolderRow row = requireActiveFolder(folderId);
        requireManageInParent(user.getId(), row.spaceId(), row.parentFolderId());

        UUID newParentId = request.parentFolderId();
        if (Objects.equals(row.parentFolderId(), newParentId)
                && (request.position() == null || request.position() == row.position())) {
            Counts counts = countsForFolder(user.getId(), row);
            return toView(row, counts.documents(), counts.folders());
        }

        if (newParentId != null) {
            if (newParentId.equals(folderId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Impossible de déplacer un dossier dans lui-même");
            }
            FolderRow newParent = requireActiveFolder(newParentId);
            if (!newParent.spaceId().equals(row.spaceId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Déplacement inter-espace non supporté");
            }
            if (isDescendant(folderId, newParentId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Impossible de déplacer un dossier dans son propre descendant");
            }
            requireManageInParent(user.getId(), row.spaceId(), newParentId);
            int newDepth = folderDepth(newParentId) + 1 + maxDescendantDepth(folderId);
            if (newDepth > maxDepth) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Profondeur maximale de dossiers atteinte (" + maxDepth + ")");
            }
        } else {
            requireManageInParent(user.getId(), row.spaceId(), null);
            int newDepth = 1 + maxDescendantDepth(folderId);
            if (newDepth > maxDepth) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Profondeur maximale de dossiers atteinte (" + maxDepth + ")");
            }
        }

        int position = request.position() != null
                ? request.position()
                : nextFolderPosition(row.spaceId(), newParentId);

        try {
            jdbc.update("""
                    UPDATE folders
                       SET parent_folder_id = ?, position = ?, updated_at = now()
                     WHERE id = ? AND deleted_at IS NULL
                    """,
                    newParentId, position, folderId);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un dossier portant ce nom existe déjà au même niveau");
        }

        String oldParentType = row.parentFolderId() != null ? "folder" : "space";
        UUID oldParentObj = row.parentFolderId() != null ? row.parentFolderId() : row.spaceId();
        String newParentType = newParentId != null ? "folder" : "space";
        UUID newParentObj = newParentId != null ? newParentId : row.spaceId();
        authorizationService.reparentFolder(
                folderId, oldParentType, oldParentObj, newParentType, newParentObj);

        auditService.record(user.getId(), false, AuditActions.FOLDER_MOVED,
                "folder", folderId,
                Map.of(
                        "fromParentFolderId", row.parentFolderId() != null ? row.parentFolderId().toString() : "",
                        "toParentFolderId", newParentId != null ? newParentId.toString() : "",
                        "position", position
                ),
                null);

        FolderRow updated = requireActiveFolder(folderId);
        Counts counts = countsForFolder(user.getId(), updated);
        return toView(updated, counts.documents(), counts.folders());
    }

    @Transactional
    public TrashService.SoftDeleteResult delete(Jwt jwt, UUID folderId) {
        var user = userSyncService.syncFromJwt(jwt);
        FolderRow row = requireActiveFolder(folderId);
        requireManageInParent(user.getId(), row.spaceId(), row.parentFolderId());
        TrashService.SoftDeleteResult result = trashService.softDeleteFolder(jwt, folderId);
        return result;
    }

    @Transactional
    public Map<String, Object> moveDocument(Jwt jwt, UUID documentId, MoveDocumentRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");

        DocRow doc = requireActiveDocument(documentId);
        UUID targetFolderId = request.folderId();

        if (targetFolderId != null) {
            FolderRow target = requireActiveFolder(targetFolderId);
            if (!target.spaceId().equals(doc.spaceId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Déplacement inter-espace non supporté");
            }
            authorizationService.requireFolderRelation(user.getId(), targetFolderId, "editor");
        } else {
            authorizationService.requireSpaceRelation(user.getId(), doc.spaceId(), "editor");
        }

        if (Objects.equals(doc.folderId(), targetFolderId)
                && (request.position() == null || request.position() == doc.position())) {
            return Map.of(
                    "id", documentId,
                    "folderId", targetFolderId != null ? targetFolderId : "",
                    "position", doc.position()
            );
        }

        int position = request.position() != null
                ? request.position()
                : nextDocumentPosition(doc.spaceId(), targetFolderId);

        jdbc.update("""
                UPDATE documents
                   SET folder_id = ?, position = ?, updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                """,
                targetFolderId, position, documentId);

        String oldParent = doc.folderId() != null
                ? "folder:" + doc.folderId()
                : "space:" + doc.spaceId();
        String newParent = targetFolderId != null
                ? "folder:" + targetFolderId
                : "space:" + doc.spaceId();
        authorizationService.reparentDocument(documentId, oldParent, newParent, doc.visibility());

        auditService.record(user.getId(), false, AuditActions.DOCUMENT_MOVED,
                "document", documentId,
                Map.of(
                        "fromFolderId", doc.folderId() != null ? doc.folderId().toString() : "",
                        "toFolderId", targetFolderId != null ? targetFolderId.toString() : "",
                        "position", position,
                        "spaceId", doc.spaceId().toString()
                ),
                null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", documentId);
        out.put("folderId", targetFolderId);
        out.put("position", position);
        out.put("spaceId", doc.spaceId());
        return out;
    }

    /**
     * Arbre filtré : dossiers viewer + documents viewer, compteurs post-filtrage.
     * Vérifications bornées à {@link DocumentScope#space(UUID)}.
     */
    @Transactional(readOnly = true)
    public SpaceTreeResponse tree(Jwt jwt, UUID spaceId, Integer depth) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireSpaceRelation(user.getId(), spaceId, "viewer");
        String spaceName = requireActiveSpace(spaceId);
        int maxTreeDepth = depth == null || depth <= 0 ? maxDepth : Math.min(depth, maxDepth);

        List<FolderRow> allFolders = jdbc.query("""
                SELECT id, space_id, parent_folder_id, name, position, created_by, created_at, updated_at
                  FROM folders
                 WHERE space_id = ? AND deleted_at IS NULL
                 ORDER BY position ASC, lower(name) ASC
                """,
                (rs, i) -> mapFolder(rs), spaceId);

        List<UUID> viewableFolderIds = authorizationService.filterByFolderViewer(
                user.getId(), allFolders.stream().map(FolderRow::id).toList());
        Set<UUID> visibleFolders = new HashSet<>(viewableFolderIds);

        List<UUID> viewableDocIds = authorizationService.listViewableDocumentIds(
                user.getId(), DocumentScope.space(spaceId));
        Set<UUID> visibleDocs = new HashSet<>(viewableDocIds);

        List<DocRow> docs = List.of();
        if (!viewableDocIds.isEmpty()) {
            String placeholders = String.join(",", viewableDocIds.stream().map(u -> "?").toList());
            List<Object> args = new ArrayList<>();
            args.add(spaceId);
            args.addAll(viewableDocIds);
            docs = jdbc.query(
                    """
                    SELECT id, space_id, folder_id, title, status, visibility, position
                      FROM documents
                     WHERE space_id = ? AND deleted_at IS NULL
                       AND id IN ("""
                            + placeholders
                            + """
                       )
                     ORDER BY position ASC, lower(title) ASC
                    """,
                    (rs, i) -> mapDoc(rs),
                    args.toArray());
        }

        Map<UUID, List<FolderRow>> childrenByParent = new HashMap<>();
        for (FolderRow f : allFolders) {
            if (!visibleFolders.contains(f.id())) {
                continue;
            }
            childrenByParent
                    .computeIfAbsent(f.parentFolderId(), k -> new ArrayList<>())
                    .add(f);
        }

        Map<UUID, List<DocRow>> docsByFolder = new HashMap<>();
        List<DocRow> rootDocs = new ArrayList<>();
        for (DocRow d : docs) {
            if (!visibleDocs.contains(d.id())) {
                continue;
            }
            if (d.folderId() == null || !visibleFolders.contains(d.folderId())) {
                if (d.folderId() == null) {
                    rootDocs.add(d);
                }
                // Doc in invisible folder: omit (no leak)
                continue;
            }
            docsByFolder.computeIfAbsent(d.folderId(), k -> new ArrayList<>()).add(d);
        }

        List<TreeFolderNode> rootFolders = buildTree(
                childrenByParent.getOrDefault(null, List.of()),
                childrenByParent,
                docsByFolder,
                1,
                maxTreeDepth);

        List<TreeDocumentNode> rootDocNodes = rootDocs.stream()
                .sorted(Comparator.comparingInt(DocRow::position).thenComparing(d -> d.title().toLowerCase()))
                .map(d -> new TreeDocumentNode(d.id(), d.title(), null, d.position(), d.status()))
                .toList();

        return new SpaceTreeResponse(
                spaceId,
                spaceName,
                rootFolders,
                rootDocNodes,
                visibleFolders.size(),
                (int) docs.stream().filter(d -> visibleDocs.contains(d.id())
                        && (d.folderId() == null || visibleFolders.contains(d.folderId()))).count()
        );
    }

    private List<TreeFolderNode> buildTree(
            List<FolderRow> siblings,
            Map<UUID, List<FolderRow>> childrenByParent,
            Map<UUID, List<DocRow>> docsByFolder,
            int depth,
            int maxTreeDepth
    ) {
        List<TreeFolderNode> nodes = new ArrayList<>();
        for (FolderRow f : siblings) {
            List<DocRow> folderDocs = docsByFolder.getOrDefault(f.id(), List.of());
            List<TreeDocumentNode> docNodes = folderDocs.stream()
                    .sorted(Comparator.comparingInt(DocRow::position).thenComparing(d -> d.title().toLowerCase()))
                    .map(d -> new TreeDocumentNode(d.id(), d.title(), f.id(), d.position(), d.status()))
                    .toList();
            List<FolderRow> childFolders = childrenByParent.getOrDefault(f.id(), List.of());
            List<TreeFolderNode> nested = depth >= maxTreeDepth
                    ? List.of()
                    : buildTree(childFolders, childrenByParent, docsByFolder, depth + 1, maxTreeDepth);
            nodes.add(new TreeFolderNode(
                    f.id(),
                    f.name(),
                    f.parentFolderId(),
                    f.position(),
                    docNodes.size(),
                    childFolders.size(),
                    nested,
                    docNodes
            ));
        }
        return nodes;
    }

    /**
     * Gestion dossier : owner de l'espace, ou editor du dossier parent
     * (à la racine : editor de l'espace).
     */
    private void requireManageInParent(UUID userId, UUID spaceId, UUID parentFolderId) {
        if (authorizationService.hasRelation(userId, "space", spaceId, "owner")) {
            return;
        }
        if (parentFolderId != null) {
            authorizationService.requireFolderRelation(userId, parentFolderId, "editor");
            return;
        }
        authorizationService.requireSpaceRelation(userId, spaceId, "editor");
    }

    private Counts countsForFolder(UUID userId, FolderRow folder) {
        List<UUID> childFolderIds = jdbc.query("""
                SELECT id FROM folders
                 WHERE parent_folder_id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> (UUID) rs.getObject("id"), folder.id());
        int folderCount = authorizationService.filterByFolderViewer(userId, childFolderIds).size();

        List<UUID> docIds = jdbc.query("""
                SELECT id FROM documents
                 WHERE folder_id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> (UUID) rs.getObject("id"), folder.id());
        int docCount = authorizationService.filterByDocumentViewer(userId, docIds).size();
        return new Counts(docCount, folderCount);
    }

    private int folderDepth(UUID folderId) {
        int depth = 0;
        UUID current = folderId;
        Set<UUID> seen = new HashSet<>();
        while (current != null) {
            if (!seen.add(current)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cycle détecté dans l'arborescence");
            }
            depth++;
            current = jdbc.query("""
                    SELECT parent_folder_id FROM folders WHERE id = ? AND deleted_at IS NULL
                    """,
                    rs -> rs.next() ? (UUID) rs.getObject(1) : null,
                    current);
        }
        return depth;
    }

    /** Profondeur max des descendants sous folderId (0 si feuille). */
    private int maxDescendantDepth(UUID folderId) {
        Integer max = jdbc.queryForObject("""
                WITH RECURSIVE tree AS (
                    SELECT id, parent_folder_id, 0 AS d
                      FROM folders WHERE id = ? AND deleted_at IS NULL
                    UNION ALL
                    SELECT f.id, f.parent_folder_id, t.d + 1
                      FROM folders f
                      JOIN tree t ON f.parent_folder_id = t.id
                     WHERE f.deleted_at IS NULL
                )
                SELECT COALESCE(MAX(d), 0) FROM tree
                """, Integer.class, folderId);
        return max == null ? 0 : max;
    }

    private boolean isDescendant(UUID ancestorId, UUID candidateId) {
        Boolean found = jdbc.queryForObject("""
                WITH RECURSIVE tree AS (
                    SELECT id FROM folders WHERE id = ? AND deleted_at IS NULL
                    UNION ALL
                    SELECT f.id FROM folders f
                      JOIN tree t ON f.parent_folder_id = t.id
                     WHERE f.deleted_at IS NULL
                )
                SELECT EXISTS (SELECT 1 FROM tree WHERE id = ?)
                """, Boolean.class, ancestorId, candidateId);
        return Boolean.TRUE.equals(found);
    }

    private int nextFolderPosition(UUID spaceId, UUID parentFolderId) {
        Integer max;
        if (parentFolderId == null) {
            max = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(position), -1) FROM folders
                     WHERE space_id = ? AND parent_folder_id IS NULL AND deleted_at IS NULL
                    """, Integer.class, spaceId);
        } else {
            max = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(position), -1) FROM folders
                     WHERE parent_folder_id = ? AND deleted_at IS NULL
                    """, Integer.class, parentFolderId);
        }
        return (max == null ? -1 : max) + 1;
    }

    private int nextDocumentPosition(UUID spaceId, UUID folderId) {
        Integer max;
        if (folderId == null) {
            max = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(position), -1) FROM documents
                     WHERE space_id = ? AND folder_id IS NULL AND deleted_at IS NULL
                    """, Integer.class, spaceId);
        } else {
            max = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(position), -1) FROM documents
                     WHERE folder_id = ? AND deleted_at IS NULL
                    """, Integer.class, folderId);
        }
        return (max == null ? -1 : max) + 1;
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name requis");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name trop long");
        }
        return trimmed;
    }

    private String requireActiveSpace(UUID spaceId) {
        List<String> names = jdbc.query("""
                SELECT name FROM spaces WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> rs.getString("name"), spaceId);
        if (names.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
        }
        return names.getFirst();
    }

    private FolderRow requireActiveFolder(UUID id) {
        List<FolderRow> rows = jdbc.query("""
                SELECT id, space_id, parent_folder_id, name, position, created_by, created_at, updated_at
                  FROM folders WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> mapFolder(rs), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dossier introuvable");
        }
        return rows.getFirst();
    }

    private DocRow requireActiveDocument(UUID id) {
        List<DocRow> rows = jdbc.query("""
                SELECT id, space_id, folder_id, title, status, visibility, position
                  FROM documents WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> mapDoc(rs), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable");
        }
        return rows.getFirst();
    }

    private static FolderView toView(FolderRow row, int docs, int folders) {
        return new FolderView(
                row.id(), row.spaceId(), row.parentFolderId(), row.name(), row.position(),
                row.createdBy(), row.createdAt(), row.updatedAt(), docs, folders);
    }

    private static FolderRow mapFolder(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new FolderRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("space_id"),
                (UUID) rs.getObject("parent_folder_id"),
                rs.getString("name"),
                rs.getInt("position"),
                (UUID) rs.getObject("created_by"),
                created != null ? created.toInstant() : null,
                updated != null ? updated.toInstant() : null
        );
    }

    private static DocRow mapDoc(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("space_id"),
                (UUID) rs.getObject("folder_id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("visibility"),
                rs.getInt("position")
        );
    }

    private record FolderRow(
            UUID id, UUID spaceId, UUID parentFolderId, String name, int position,
            UUID createdBy, java.time.Instant createdAt, java.time.Instant updatedAt
    ) {}

    private record DocRow(
            UUID id, UUID spaceId, UUID folderId, String title, String status,
            String visibility, int position
    ) {}

    private record Counts(int documents, int folders) {}
}
