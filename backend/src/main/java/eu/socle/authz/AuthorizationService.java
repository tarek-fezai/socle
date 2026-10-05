// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import eu.socle.config.SocleProperties;
import eu.socle.document.DocumentVisibility;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientListObjectsRequest;
import dev.openfga.sdk.api.client.model.ClientListUsersRequest;
import dev.openfga.sdk.api.model.FgaObject;
import dev.openfga.sdk.api.model.UserTypeFilter;
import dev.openfga.sdk.api.client.model.ClientReadRequest;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.ClientWriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    public static final String WILDCARD_USER = "user:*";
    public static final String RELATION_DIRECT_ACCESS = "direct_access";

    /**
     * Présélection SQL lisible — peut sur-inclure, jamais sous-inclure.
     * Exactitude via {@link #filterByDocumentViewer}.
     */
    public static final String READABLE_PREDICATE = """
            (
                 d.visibility = 'organisation'
              OR (d.visibility = 'space' AND d.space_id = ANY (?))
              OR d.space_id = ANY (?)
              OR d.id = ANY (?)
              OR d.folder_id = ANY (?)
            )
            """;

    private static final Set<String> OBJECT_TYPES = Set.of(
            "organisation", "space", "folder", "document", "group"
    );
    private static final Set<String> RELATIONS = Set.of(
            "owner", "editor", "viewer", "member", "parent", "inherit_from", RELATION_DIRECT_ACCESS
    );
    private static final Set<String> STRUCTURAL_RELATIONS = Set.of("parent", "inherit_from");
    private static final Set<String> DIRECT_GRANT_RELATIONS = Set.of("owner", "editor", "viewer");

    private final OpenFgaClient openFgaClient;
    private final JdbcTemplate jdbc;
    private final int listObjectsMaxResults;
    private final int maxChecksPerBatchCheck;
    private final int batchCheckParallelism;
    private final int documentCheckWarnThreshold;

    @Autowired
    public AuthorizationService(
            OpenFgaClient openFgaClient,
            JdbcTemplate jdbc,
            SocleProperties properties
    ) {
        this.openFgaClient = openFgaClient;
        this.jdbc = jdbc;
        SocleProperties.OpenFga fga = properties.openfga();
        this.listObjectsMaxResults = fga != null ? fga.effectiveListObjectsMaxResults() : 1000;
        this.maxChecksPerBatchCheck = fga != null ? fga.effectiveMaxChecksPerBatchCheck() : 50;
        this.batchCheckParallelism = fga != null ? fga.effectiveBatchCheckParallelism() : 4;
        this.documentCheckWarnThreshold = fga != null ? fga.effectiveDocumentCheckWarnThreshold() : 500;
    }

    /** Constructeur tests unitaires sans JDBC / properties. */
    public AuthorizationService(OpenFgaClient openFgaClient) {
        this.openFgaClient = openFgaClient;
        this.jdbc = null;
        this.listObjectsMaxResults = 1000;
        this.maxChecksPerBatchCheck = 50;
        this.batchCheckParallelism = 4;
        this.documentCheckWarnThreshold = 500;
    }

    public int listObjectsMaxResults() {
        return listObjectsMaxResults;
    }

    public int maxChecksPerBatchCheck() {
        return maxChecksPerBatchCheck;
    }

    public void requireDocumentRelation(UUID userId, UUID documentId, String relation) {
        if (!check(user(userId), relation, document(documentId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (" + relation + ")");
        }
    }

    public void requireSpaceRelation(UUID userId, UUID spaceId, String relation) {
        if (!check(user(userId), relation, space(spaceId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès espace refusé (" + relation + ")");
        }
    }

    public void requireFolderRelation(UUID userId, UUID folderId, String relation) {
        if (!check(user(userId), relation, folder(folderId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès dossier refusé (" + relation + ")");
        }
    }

    public boolean hasRelation(UUID userId, String objectType, UUID objectId, String relation) {
        return check(user(userId), relation, objectType + ":" + objectId);
    }

    /** @deprecated créateur = editor ; conserver pour compat tests / migration. */
    @Deprecated
    public void grantDocumentOwner(UUID userId, UUID documentId) {
        writeTuple(user(userId), "owner", document(documentId));
    }

    public void grantDocumentEditor(UUID userId, UUID documentId) {
        writeAtomic(
                List.of(
                        tuple(user(userId), "editor", document(documentId)),
                        tuple(user(userId), RELATION_DIRECT_ACCESS, document(documentId))
                ),
                List.of());
    }

    /**
     * Création document : un seul Write OpenFGA (parent, inherit_from?, user:*?, editor, direct_access).
     *
     * @return les tuples écrits (pour compensation si échec ultérieur)
     */
    public List<AccessTuple> provisionDocumentAccess(
            UUID documentId, UUID spaceId, UUID creatorId, String visibility
    ) {
        return provisionDocumentAccess(documentId, spaceId, null, creatorId, visibility);
    }

    /**
     * Création document : parent = dossier si fourni, sinon espace.
     * {@code restricted} → pas d'{@code inherit_from}.
     */
    public List<AccessTuple> provisionDocumentAccess(
            UUID documentId, UUID spaceId, UUID folderId, UUID creatorId, String visibility
    ) {
        String vis = DocumentVisibility.requireValid(
                visibility == null ? DocumentVisibility.SPACE : visibility);
        String doc = document(documentId);
        String parentObj = folderId != null ? folder(folderId) : space(spaceId);
        List<ClientTupleKey> writes = new ArrayList<>();
        writes.add(tuple(parentObj, "parent", doc));
        if (DocumentVisibility.inheritsFromParent(vis)) {
            writes.add(tuple(parentObj, "inherit_from", doc));
        }
        if (DocumentVisibility.isOrganisationWide(vis)) {
            writes.add(tuple(WILDCARD_USER, "viewer", doc));
        }
        writes.add(tuple(user(creatorId), "editor", doc));
        writes.add(tuple(user(creatorId), RELATION_DIRECT_ACCESS, doc));
        writeAtomic(writes, List.of());
        return writes.stream()
                .map(t -> new AccessTuple(t.getUser(), t.getRelation(), t.getObject()))
                .toList();
    }

    /** Création dossier : {@code parent} + {@code inherit_from} vers espace ou dossier parent. */
    public void provisionFolderAccess(UUID folderId, String parentType, UUID parentId) {
        reparentFolder(folderId, null, null, parentType, parentId);
    }

    /**
     * Déplacement dossier : retire l'ancien parent/inherit_from, écrit le nouveau — un Write atomique.
     */
    public void reparentFolder(
            UUID folderId,
            String oldParentType,
            UUID oldParentId,
            String newParentType,
            UUID newParentId
    ) {
        String folderObj = folder(folderId);
        String newParent = newParentType + ":" + newParentId;
        List<ClientTupleKey> writes = List.of(
                tuple(newParent, "parent", folderObj),
                tuple(newParent, "inherit_from", folderObj)
        );
        List<ClientTupleKeyWithoutCondition> deletes = new ArrayList<>();
        if (oldParentType != null && oldParentId != null) {
            String oldParent = oldParentType + ":" + oldParentId;
            if (!oldParent.equals(newParent)) {
                deletes.add(deleteKey(oldParent, "parent", folderObj));
                deletes.add(deleteKey(oldParent, "inherit_from", folderObj));
            }
        }
        writeAtomic(writes, deletes);
    }

    /**
     * Déplacement document dans le même espace : remplace parent (+ inherit_from si non restricted).
     */
    public void reparentDocument(
            UUID documentId,
            String oldParentObject,
            String newParentObject,
            String visibility
    ) {
        String vis = DocumentVisibility.requireValid(
                visibility == null ? DocumentVisibility.SPACE : visibility);
        String doc = document(documentId);
        List<ClientTupleKey> writes = new ArrayList<>();
        List<ClientTupleKeyWithoutCondition> deletes = new ArrayList<>();
        writes.add(tuple(newParentObject, "parent", doc));
        if (DocumentVisibility.inheritsFromParent(vis)) {
            writes.add(tuple(newParentObject, "inherit_from", doc));
        }
        if (oldParentObject != null && !oldParentObject.equals(newParentObject)) {
            deletes.add(deleteKey(oldParentObject, "parent", doc));
            deletes.add(deleteKey(oldParentObject, "inherit_from", doc));
        }
        writeAtomic(writes, deletes);
    }

    /** Compensation : suppression groupée des tuples de création. */
    public void revokeDocumentAccess(List<AccessTuple> provisioned) {
        if (provisioned == null || provisioned.isEmpty()) {
            return;
        }
        List<ClientTupleKeyWithoutCondition> deletes = provisioned.stream()
                .map(t -> deleteKey(t.user(), t.relation(), t.object()))
                .toList();
        writeAtomic(List.of(), deletes);
    }

    /**
     * Lie le document à son parent — déprécié au profit de {@link #provisionDocumentAccess}.
     * Conservé pour compat tests.
     */
    public void linkDocumentToSpace(UUID documentId, UUID spaceId, String visibility) {
        String vis = visibility == null ? DocumentVisibility.SPACE : visibility;
        List<ClientTupleKey> writes = new ArrayList<>();
        writes.add(tuple(space(spaceId), "parent", document(documentId)));
        if (DocumentVisibility.inheritsFromParent(vis)) {
            writes.add(tuple(space(spaceId), "inherit_from", document(documentId)));
        }
        if (DocumentVisibility.isOrganisationWide(vis)) {
            writes.add(tuple(WILDCARD_USER, "viewer", document(documentId)));
        }
        writeAtomic(writes, List.of());
    }

    public void linkDocumentToSpace(UUID documentId, UUID spaceId) {
        linkDocumentToSpace(documentId, spaceId, DocumentVisibility.SPACE);
    }

    public void linkDocumentToFolder(UUID documentId, UUID folderId, String visibility) {
        String vis = visibility == null ? DocumentVisibility.SPACE : visibility;
        List<ClientTupleKey> writes = new ArrayList<>();
        writes.add(tuple(folder(folderId), "parent", document(documentId)));
        if (DocumentVisibility.inheritsFromParent(vis)) {
            writes.add(tuple(folder(folderId), "inherit_from", document(documentId)));
        }
        if (DocumentVisibility.isOrganisationWide(vis)) {
            writes.add(tuple(WILDCARD_USER, "viewer", document(documentId)));
        }
        writeAtomic(writes, List.of());
    }

    public void linkDocumentToFolder(UUID documentId, UUID folderId) {
        linkDocumentToFolder(documentId, folderId, DocumentVisibility.SPACE);
    }

    public void linkFolderToParent(UUID folderId, String parentType, UUID parentId) {
        provisionFolderAccess(folderId, parentType, parentId);
    }

    public void grantOrganisationViewer(UUID documentId) {
        writeTuple(WILDCARD_USER, "viewer", document(documentId));
    }

    public void revokeOrganisationViewer(UUID documentId) {
        deleteTuple(WILDCARD_USER, "viewer", document(documentId));
    }

    /**
     * Applique les tuples FGA pour un changement de visibilité en un seul Write atomique.
     */
    public void applyVisibilityTuples(UUID documentId, String parentObject, String from, String to) {
        String fromV = DocumentVisibility.requireValid(from);
        String toV = DocumentVisibility.requireValid(to);
        if (fromV.equals(toV)) {
            return;
        }
        String doc = document(documentId);
        List<ClientTupleKey> writes = new ArrayList<>();
        List<ClientTupleKeyWithoutCondition> deletes = new ArrayList<>();

        boolean hadInherit = DocumentVisibility.inheritsFromParent(fromV);
        boolean needsInherit = DocumentVisibility.inheritsFromParent(toV);
        if (!hadInherit && needsInherit) {
            writes.add(tuple(parentObject, "inherit_from", doc));
        } else if (hadInherit && !needsInherit) {
            deletes.add(deleteKey(parentObject, "inherit_from", doc));
        }

        boolean hadOrg = DocumentVisibility.isOrganisationWide(fromV);
        boolean needsOrg = DocumentVisibility.isOrganisationWide(toV);
        if (!hadOrg && needsOrg) {
            writes.add(tuple(WILDCARD_USER, "viewer", doc));
        } else if (hadOrg && !needsOrg) {
            deletes.add(deleteKey(WILDCARD_USER, "viewer", doc));
        }

        writeAtomic(writes, deletes);
    }

    /**
     * Écriture immédiate d'une permission (Access.dc.html) — dual-write {@code direct_access}
     * sur document pour ListObjects à faible cardinalité.
     */
    public void grantPermission(
            String objectType,
            UUID objectId,
            String relation,
            String subjectType,
            UUID subjectId
    ) {
        validatePermissionArgs(objectType, relation, subjectType);
        String subject = subjectKey(subjectType, subjectId, relation);
        String obj = objectType + ":" + objectId;
        List<ClientTupleKey> writes = new ArrayList<>();
        writes.add(tuple(subject, relation, obj));
        if ("document".equals(objectType) && DIRECT_GRANT_RELATIONS.contains(relation)) {
            writes.add(tuple(subject, RELATION_DIRECT_ACCESS, obj));
        }
        writeAtomic(writes, List.of());
    }

    public void revokePermission(
            String objectType,
            UUID objectId,
            String relation,
            String subjectType,
            UUID subjectId
    ) {
        validatePermissionArgs(objectType, relation, subjectType);
        String subject = subjectKey(subjectType, subjectId, relation);
        String obj = objectType + ":" + objectId;
        List<ClientTupleKeyWithoutCondition> deletes = new ArrayList<>();
        deletes.add(deleteKey(subject, relation, obj));
        if ("document".equals(objectType) && DIRECT_GRANT_RELATIONS.contains(relation)) {
            // Best-effort : retirer direct_access si plus aucun grant — hors chemin critique
            // (direct_access n'accorde aucun droit ; drift orphelin via admin).
            boolean stillHasGrant = readTuples(obj).stream()
                    .anyMatch(t -> subject.equals(t.user())
                            && DIRECT_GRANT_RELATIONS.contains(t.relation())
                            && !relation.equals(t.relation()));
            if (!stillHasGrant) {
                deletes.add(deleteKey(subject, RELATION_DIRECT_ACCESS, obj));
            }
        }
        writeAtomic(List.of(), deletes);
    }

    public List<AccessTuple> listPermissions(String objectType, UUID objectId) {
        if (!OBJECT_TYPES.contains(objectType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "objectType invalide");
        }
        return readTuples(objectType + ":" + objectId).stream()
                .filter(t -> !STRUCTURAL_RELATIONS.contains(t.relation()))
                .filter(t -> !RELATION_DIRECT_ACCESS.equals(t.relation()))
                .filter(t -> !WILDCARD_USER.equals(t.user()))
                .toList();
    }

    public List<AccessEntry> listAccessEntries(String objectType, UUID objectId) {
        if (!OBJECT_TYPES.contains(objectType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "objectType invalide");
        }
        List<AccessEntry> entries = new ArrayList<>();
        String objectKey = objectType + ":" + objectId;

        for (AccessTuple t : readTuples(objectKey)) {
            if (STRUCTURAL_RELATIONS.contains(t.relation()) || RELATION_DIRECT_ACCESS.equals(t.relation())) {
                continue;
            }
            if (WILDCARD_USER.equals(t.user())) {
                entries.add(new AccessEntry(
                        t.user(), "wildcard", null, t.relation(), "organisation", null, false));
                continue;
            }
            entries.add(toEntry(t, classifyDirect(t.user()), null, true));
        }

        for (AccessTuple parentLink : readTuples(objectKey)) {
            if (!"parent".equals(parentLink.relation()) && !"inherit_from".equals(parentLink.relation())) {
                continue;
            }
            collectInherited(parentLink.user(), entries, new HashSet<>());
        }

        return List.copyOf(entries);
    }

    /**
     * Présélection SQL bornée au {@link DocumentScope}, puis {@link #filterByDocumentViewer}.
     * Le prédicat lisible (organisation / S_view / S_owner / D_direct / F_view) est inchangé ;
     * le scope est appliqué <strong>avant</strong> les Checks OpenFGA.
     *
     * <p>{@link DocumentScope#global()} — cas explicitement instance-wide (liste paginée
     * {@code /documents}). Préférer space / folder / tag / ids.
     */
    public List<UUID> listViewableDocumentIds(UUID userId, DocumentScope docScope) {
        if (docScope == null) {
            throw new IllegalArgumentException(
                    "DocumentScope requis — passer DocumentScope#global explicitement");
        }
        if (docScope.isEmptyIds()) {
            return List.of();
        }
        if (jdbc == null) {
            List<UUID> all = listObjectsDocumentIds(userId);
            return applyScopeInMemory(all, docScope);
        }
        List<UUID> candidates = preselectDocumentIds(userId, docScope);
        return filterByDocumentViewer(userId, candidates, docScope.label());
    }

    /**
     * Page de candidats présélectionnés (sans Check) — pour pagination avec refill authz
     * (Search / {@code /documents}).
     */
    public List<UUID> preselectDocumentIdsPage(
            UUID userId, DocumentScope docScope, int limit, int offset
    ) {
        if (docScope == null) {
            throw new IllegalArgumentException(
                    "DocumentScope requis — passer DocumentScope#global explicitement");
        }
        DocumentScope scope = docScope;
        if (scope.isEmptyIds() || jdbc == null || limit <= 0) {
            return List.of();
        }
        ReadableScope readable = readableScope(userId);
        List<UUID> folderTree = resolveFolderTree(scope.folderId());
        return jdbc.query(
                """
                SELECT d.id
                  FROM documents d
                 WHERE d.deleted_at IS NULL
                   AND %s
                   AND %s
                 ORDER BY d.updated_at DESC
                 LIMIT ? OFFSET ?
                """.formatted(READABLE_PREDICATE, scopeSqlPredicate(scope)),
                ps -> {
                    int idx = bindReadableAndScope(ps, 1, readable, scope, folderTree);
                    ps.setInt(idx++, limit);
                    ps.setInt(idx, offset);
                },
                (rs, i) -> (UUID) rs.getObject("id"));
    }

    public int countPreselectedDocuments(UUID userId, DocumentScope docScope) {
        if (docScope == null) {
            throw new IllegalArgumentException(
                    "DocumentScope requis — passer DocumentScope#global explicitement");
        }
        DocumentScope scope = docScope;
        if (scope.isEmptyIds() || jdbc == null) {
            return 0;
        }
        ReadableScope readable = readableScope(userId);
        List<UUID> folderTree = resolveFolderTree(scope.folderId());
        return jdbc.query(
                """
                SELECT count(*)::int
                  FROM documents d
                 WHERE d.deleted_at IS NULL
                   AND %s
                   AND %s
                """.formatted(READABLE_PREDICATE, scopeSqlPredicate(scope)),
                ps -> bindReadableAndScope(ps, 1, readable, scope, folderTree),
                (rs, i) -> rs.getInt(1)).stream().findFirst().orElse(0);
    }

    private List<UUID> preselectDocumentIds(UUID userId, DocumentScope scope) {
        ReadableScope readable = readableScope(userId);
        List<UUID> folderTree = resolveFolderTree(scope.folderId());
        return jdbc.query(
                """
                SELECT d.id
                  FROM documents d
                 WHERE d.deleted_at IS NULL
                   AND %s
                   AND %s
                """.formatted(READABLE_PREDICATE, scopeSqlPredicate(scope)),
                ps -> bindReadableAndScope(ps, 1, readable, scope, folderTree),
                (rs, i) -> (UUID) rs.getObject("id"));
    }

    private static String scopeSqlPredicate(DocumentScope scope) {
        // space / folder / tag / ids — tous optionnels ; true si global
        return """
                (
                     (?::uuid IS NULL OR d.space_id = ?::uuid)
                 AND (?::boolean OR d.folder_id = ANY (?))
                 AND (?::uuid IS NULL OR EXISTS (
                       SELECT 1 FROM document_tags dt
                        WHERE dt.document_id = d.id AND dt.tag_id = ?::uuid
                     ))
                 AND (?::boolean OR d.id = ANY (?))
                )
                """;
    }

    /**
     * Bind les quatre tableaux UUID de {@link #READABLE_PREDICATE}
     * (S_view, S_owner, D_direct, F_view).
     *
     * @return index du prochain paramètre à binder
     */
    public int bindReadable(
            java.sql.PreparedStatement ps,
            int startIdx,
            ReadableScope readable
    ) throws java.sql.SQLException {
        int idx = startIdx;
        Array sView = ps.getConnection().createArrayOf("uuid", readable.spaceViewerIds().toArray());
        Array sOwner = ps.getConnection().createArrayOf("uuid", readable.spaceOwnerIds().toArray());
        Array dDirect = ps.getConnection().createArrayOf("uuid", readable.directDocumentIds().toArray());
        Array fView = ps.getConnection().createArrayOf("uuid", readable.folderViewerIds().toArray());
        ps.setArray(idx++, sView);
        ps.setArray(idx++, sOwner);
        ps.setArray(idx++, dDirect);
        ps.setArray(idx++, fView);
        return idx;
    }

    private int bindReadableAndScope(
            java.sql.PreparedStatement ps,
            int startIdx,
            ReadableScope readable,
            DocumentScope scope,
            List<UUID> folderTree
    ) throws java.sql.SQLException {
        int idx = bindReadable(ps, startIdx, readable);

        UUID spaceId = scope.spaceId();
        if (spaceId == null) {
            ps.setObject(idx++, null);
            ps.setObject(idx++, null);
        } else {
            ps.setObject(idx++, spaceId);
            ps.setObject(idx++, spaceId);
        }

        boolean filterFolder = scope.folderId() != null;
        ps.setBoolean(idx++, !filterFolder);
        Array folders = ps.getConnection().createArrayOf(
                "uuid",
                (filterFolder ? folderTree : List.<UUID>of()).toArray());
        ps.setArray(idx++, folders);

        UUID tagId = scope.tagId();
        if (tagId == null) {
            ps.setObject(idx++, null);
            ps.setObject(idx++, null);
        } else {
            ps.setObject(idx++, tagId);
            ps.setObject(idx++, tagId);
        }

        boolean filterIds = scope.ids() != null;
        ps.setBoolean(idx++, !filterIds);
        Array idArr = ps.getConnection().createArrayOf(
                "uuid",
                (filterIds ? scope.ids() : List.<UUID>of()).toArray());
        ps.setArray(idx++, idArr);
        return idx;
    }

    private List<UUID> resolveFolderTree(UUID rootFolderId) {
        if (rootFolderId == null || jdbc == null) {
            return List.of();
        }
        return jdbc.query("""
                WITH RECURSIVE tree AS (
                  SELECT id FROM folders WHERE id = ? AND deleted_at IS NULL
                  UNION ALL
                  SELECT f.id FROM folders f
                    JOIN tree t ON f.parent_folder_id = t.id
                   WHERE f.deleted_at IS NULL
                )
                SELECT id FROM tree
                """, (rs, i) -> (UUID) rs.getObject("id"), rootFolderId);
    }

    private static List<UUID> applyScopeInMemory(List<UUID> ids, DocumentScope scope) {
        if (scope.isGlobal()) {
            return ids;
        }
        if (scope.ids() != null) {
            Set<UUID> want = new HashSet<>(scope.ids());
            return ids.stream().filter(want::contains).toList();
        }
        // sans JDBC : space/folder/tag non appliquables → liste vide (tests mock JDBC)
        return List.of();
    }

    /**
     * Ensembles ListObjects pour la présélection SQL (Search / list — peut sur-inclure).
     */
    public ReadableScope readableScope(UUID userId) {
        List<UUID> sView = listObjectsOfType(userId, "viewer", "space");
        List<UUID> sOwner = listObjectsOfType(userId, "owner", "space");
        List<UUID> dDirect = listObjectsOfType(userId, RELATION_DIRECT_ACCESS, "document");
        List<UUID> fView = listObjectsOfType(userId, "viewer", "folder");
        warnIfAtCeiling("space/viewer", sView.size());
        warnIfAtCeiling("space/owner", sOwner.size());
        warnIfAtCeiling("document/direct_access", dDirect.size());
        warnIfAtCeiling("folder/viewer", fView.size());
        return new ReadableScope(sView, sOwner, dDirect, fView);
    }

    public List<UUID> filterByDocumentViewer(UUID userId, Collection<UUID> documentIds) {
        return filterByDocumentViewer(userId, documentIds, "unscoped");
    }

    /**
     * Vérification finale OpenFGA {@code viewer} — BatchCheck découpé + parallélisme borné.
     */
    public List<UUID> filterByDocumentViewer(
            UUID userId, Collection<UUID> documentIds, String scopeLabel
    ) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ordered = List.copyOf(documentIds);
        logCheckVolume(ordered.size(), scopeLabel);

        String fgaUser = user(userId);
        List<ClientBatchCheckItem> checks = new ArrayList<>(ordered.size());
        for (UUID id : ordered) {
            checks.add(new ClientBatchCheckItem()
                    .user(fgaUser)
                    .relation("viewer")
                    ._object(document(id))
                    .correlationId(id.toString()));
        }
        Set<UUID> allowed = runBatchedChecks(fgaUser, checks);
        return ordered.stream().filter(allowed::contains).toList();
    }

    /** Relations OpenFGA brutes nécessaires au calcul des droits d'un document (un seul BatchCheck). */
    public record DocumentPermissionChecks(
            boolean documentOwner,
            boolean documentEditor,
            boolean documentViewer,
            boolean spaceOwner,
            boolean spaceViewer
    ) {
        public static final DocumentPermissionChecks NONE =
                new DocumentPermissionChecks(false, false, false, false, false);
    }

    private static final String PERM_DOC_OWNER = "doc-owner";
    private static final String PERM_DOC_EDITOR = "doc-editor";
    private static final String PERM_DOC_VIEWER = "doc-viewer";
    private static final String PERM_SPACE_OWNER = "space-owner";
    private static final String PERM_SPACE_VIEWER = "space-viewer";

    /**
     * Droits bruts de l'utilisateur sur un document et son espace — <strong>un seul</strong>
     * BatchCheck OpenFGA (5 checks). Repli sur des Checks unitaires si BatchCheck est indisponible.
     */
    public DocumentPermissionChecks batchCheckDocumentPermissions(
            UUID userId, UUID documentId, UUID spaceId
    ) {
        String fgaUser = user(userId);
        String doc = document(documentId);
        String sp = space(spaceId);
        List<ClientBatchCheckItem> checks = List.of(
                batchItem(fgaUser, "owner", doc, PERM_DOC_OWNER),
                batchItem(fgaUser, "editor", doc, PERM_DOC_EDITOR),
                batchItem(fgaUser, "viewer", doc, PERM_DOC_VIEWER),
                batchItem(fgaUser, "owner", sp, PERM_SPACE_OWNER),
                batchItem(fgaUser, "viewer", sp, PERM_SPACE_VIEWER));
        Set<String> allowed = new HashSet<>();
        try {
            var response = openFgaClient.batchCheck(ClientBatchCheckRequest.ofChecks(checks)).join();
            for (ClientBatchCheckSingleResponse r : response.getResult()) {
                if (r.isAllowed() && r.getCorrelationId() != null) {
                    allowed.add(r.getCorrelationId());
                }
            }
        } catch (Exception batchEx) {
            log.debug("OpenFGA BatchCheck indisponible (permissions document) — fallback Checks", batchEx);
            allowed.clear();
            for (ClientBatchCheckItem item : checks) {
                if (check(fgaUser, item.getRelation(), item.getObject())) {
                    allowed.add(item.getCorrelationId());
                }
            }
        }
        return new DocumentPermissionChecks(
                allowed.contains(PERM_DOC_OWNER),
                allowed.contains(PERM_DOC_EDITOR),
                allowed.contains(PERM_DOC_VIEWER),
                allowed.contains(PERM_SPACE_OWNER),
                allowed.contains(PERM_SPACE_VIEWER));
    }

    private static ClientBatchCheckItem batchItem(
            String fgaUser, String relation, String object, String correlationId
    ) {
        return new ClientBatchCheckItem()
                .user(fgaUser)
                .relation(relation)
                ._object(object)
                .correlationId(correlationId);
    }

    private void logCheckVolume(int count, String scopeLabel) {
        log.debug("OpenFGA document viewer checks: count={} scope={}", count, scopeLabel);
        if (count >= documentCheckWarnThreshold) {
            log.warn(
                    "OpenFGA document viewer checks élevé: count={} scope={} (seuil={})",
                    count, scopeLabel, documentCheckWarnThreshold);
        }
    }

    private Set<UUID> runBatchedChecks(String fgaUser, List<ClientBatchCheckItem> checks) {
        Set<UUID> allowed = new HashSet<>();
        List<List<ClientBatchCheckItem>> chunks = partition(checks, maxChecksPerBatchCheck);
        try {
            int parallelism = Math.min(batchCheckParallelism, Math.max(1, chunks.size()));
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(parallelism);
            try {
                List<java.util.concurrent.CompletableFuture<Set<UUID>>> futures = new ArrayList<>();
                for (List<ClientBatchCheckItem> chunk : chunks) {
                    futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> batchCheckChunk(chunk), pool));
                }
                for (var f : futures) {
                    allowed.addAll(f.join());
                }
            } finally {
                pool.shutdown();
            }
        } catch (Exception batchEx) {
            log.debug("OpenFGA BatchCheck indisponible — fallback Checks parallèles", batchEx);
            allowed.addAll(fallbackParallelChecks(fgaUser, checks));
        }
        return allowed;
    }

    private Set<UUID> batchCheckChunk(List<ClientBatchCheckItem> chunk) {
        try {
            var response = openFgaClient.batchCheck(ClientBatchCheckRequest.ofChecks(chunk)).join();
            Set<UUID> allowed = new HashSet<>();
            for (ClientBatchCheckSingleResponse r : response.getResult()) {
                if (r.isAllowed() && r.getCorrelationId() != null) {
                    allowed.add(UUID.fromString(r.getCorrelationId()));
                }
            }
            return allowed;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Set<UUID> fallbackParallelChecks(String fgaUser, List<ClientBatchCheckItem> checks) {
        Set<UUID> allowed = new HashSet<>();
        int parallelism = Math.min(batchCheckParallelism, Math.max(1, checks.size()));
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(parallelism);
        try {
            List<CompletableFutureCheck> futures = new ArrayList<>();
            for (ClientBatchCheckItem item : checks) {
                UUID id = UUID.fromString(item.getCorrelationId());
                futures.add(new CompletableFutureCheck(
                        id,
                        java.util.concurrent.CompletableFuture.supplyAsync(
                                () -> check(fgaUser, item.getRelation(), item.getObject()),
                                pool)));
            }
            for (CompletableFutureCheck f : futures) {
                try {
                    if (Boolean.TRUE.equals(f.future().join())) {
                        allowed.add(f.documentId());
                    }
                } catch (Exception e) {
                    log.error("OpenFGA check failed for document {}", f.documentId(), e);
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenFGA indisponible");
                }
            }
        } finally {
            pool.shutdown();
        }
        return allowed;
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        if (items.isEmpty()) {
            return List.of();
        }
        int chunk = Math.max(1, size);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += chunk) {
            out.add(items.subList(i, Math.min(i + chunk, items.size())));
        }
        return out;
    }

    private record CompletableFutureCheck(
            UUID documentId, java.util.concurrent.CompletableFuture<Boolean> future
    ) {}

    /** @deprecated préférer {@link #listViewableDocumentIds(UUID, DocumentScope)}. Conservé pour tests legacy. */
    public List<UUID> listObjectsDocumentIds(UUID userId) {
        List<UUID> ids = listObjectsOfType(userId, "viewer", "document");
        warnIfAtCeiling("document/viewer", ids.size());
        return ids;
    }

    public List<UUID> listViewableSpaceIds(UUID userId) {
        return listObjectsOfType(userId, "viewer", "space");
    }

    public List<UUID> listOwnedSpaceIds(UUID userId) {
        return listObjectsOfType(userId, "owner", "space");
    }

    /**
     * Membres d'un espace = utilisateurs {@code viewer} (direct, groupe, hérité) — OpenFGA ListUsers.
     * Utilisé pour figer la taille d'audience d'une campagne d'attestation.
     */
    public Set<UUID> listSpaceMemberIds(UUID spaceId) {
        try {
            var response = openFgaClient.listUsers(new ClientListUsersRequest()
                            ._object(new FgaObject().type("space").id(spaceId.toString()))
                            .relation("viewer")
                            .userFilters(List.of(new UserTypeFilter().type("user"))))
                    .join();
            Set<UUID> members = new LinkedHashSet<>();
            if (response.getUsers() == null) {
                return members;
            }
            for (var u : response.getUsers()) {
                var obj = u.getObject();
                if (obj == null || !"user".equals(obj.getType()) || obj.getId() == null) {
                    continue;
                }
                try {
                    members.add(UUID.fromString(obj.getId()));
                } catch (IllegalArgumentException ignored) {
                    // user:* ou identifiant non UUID — ignoré
                }
            }
            return members;
        } catch (Exception e) {
            log.error("OpenFGA listUsers failed space={}", spaceId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenFGA indisponible");
        }
    }

    /** ListObjects folder#viewer — plafonné ; croiser ensuite avec les dossiers de l'espace. */
    public List<UUID> listViewableFolderIds(UUID userId) {
        List<UUID> ids = listObjectsOfType(userId, "viewer", "folder");
        warnIfAtCeiling("F_view", ids.size());
        return ids;
    }

    public List<UUID> filterByFolderViewer(UUID userId, Collection<UUID> folderIds) {
        if (folderIds == null || folderIds.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = folderIds.stream().distinct().toList();
        String fgaUser = user(userId);
        List<ClientBatchCheckItem> checks = new ArrayList<>(distinct.size());
        for (UUID id : distinct) {
            checks.add(new ClientBatchCheckItem()
                    .user(fgaUser)
                    .relation("viewer")
                    ._object(folder(id))
                    .correlationId(id.toString()));
        }
        Set<UUID> allowed = runBatchedChecks(fgaUser, checks);
        return distinct.stream().filter(allowed::contains).toList();
    }

    public List<UUID> listDirectAccessDocumentIds(UUID userId) {
        return listObjectsOfType(userId, RELATION_DIRECT_ACCESS, "document");
    }

    public List<AccessTuple> readAllTuples(String objectType, UUID objectId) {
        return readTuples(objectType + ":" + objectId);
    }

    public void convertDirectOwnerToEditor(UUID documentId, String fgaUser) {
        writeAtomic(
                List.of(
                        tuple(fgaUser, "editor", document(documentId)),
                        tuple(fgaUser, RELATION_DIRECT_ACCESS, document(documentId))
                ),
                List.of(deleteKey(fgaUser, "owner", document(documentId))));
    }

    public void ensureInheritFrom(UUID documentId, String parentObject) {
        writeTuple(parentObject, "inherit_from", document(documentId));
    }

    public void ensureDirectAccess(UUID documentId, String fgaUser) {
        writeTuple(fgaUser, RELATION_DIRECT_ACCESS, document(documentId));
    }

    /** Réparation drift : retire un {@code direct_access} orphelin (n'accorde aucun droit). */
    public void deleteDirectAccessTuple(String fgaUser, UUID documentId) {
        writeAtomic(List.of(), List.of(deleteKey(fgaUser, RELATION_DIRECT_ACCESS, document(documentId))));
    }

    private List<UUID> listObjectsOfType(UUID userId, String relation, String type) {
        try {
            var response = openFgaClient.listObjects(new ClientListObjectsRequest()
                            .user(user(userId))
                            .relation(relation)
                            .type(type))
                    .join();
            String prefix = type + ":";
            return response.getObjects().stream()
                    .map(obj -> obj.replace(prefix, ""))
                    .map(UUID::fromString)
                    .toList();
        } catch (Exception e) {
            log.error("OpenFGA listObjects failed type={} relation={}", type, relation, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenFGA indisponible");
        }
    }

    private void warnIfAtCeiling(String label, int size) {
        if (size >= listObjectsMaxResults) {
            log.warn(
                    "OpenFGA ListObjects ({}) a renvoyé {} résultat(s) — égal au plafond ({}). "
                            + "Des résultats peuvent manquer.",
                    label, size, listObjectsMaxResults);
        }
    }

    private void collectInherited(String parentObject, List<AccessEntry> out, Set<String> visited) {
        if (parentObject == null || !parentObject.contains(":") || !visited.add(parentObject)) {
            return;
        }
        for (AccessTuple t : readTuples(parentObject)) {
            if (STRUCTURAL_RELATIONS.contains(t.relation())) {
                collectInherited(t.user(), out, visited);
                continue;
            }
            if (RELATION_DIRECT_ACCESS.equals(t.relation())) {
                continue;
            }
            out.add(toEntry(t, "inherited", parentObject, false));
        }
    }

    List<AccessTuple> readTuples(String object) {
        try {
            var response = openFgaClient.read(new ClientReadRequest()._object(object)).join();
            return response.getTuples().stream()
                    .map(t -> {
                        var key = t.getKey();
                        return new AccessTuple(key.getUser(), key.getRelation(), key.getObject());
                    })
                    .toList();
        } catch (Exception e) {
            log.error("OpenFGA read failed for {}", object, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenFGA indisponible");
        }
    }

    private void validatePermissionArgs(String objectType, String relation, String subjectType) {
        if (!OBJECT_TYPES.contains(objectType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "objectType invalide");
        }
        if (!RELATIONS.contains(relation)
                || STRUCTURAL_RELATIONS.contains(relation)
                || RELATION_DIRECT_ACCESS.equals(relation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relation invalide");
        }
        if (!"user".equals(subjectType) && !"group".equals(subjectType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectType invalide (user|group)");
        }
        if ("group".equals(objectType) && !"member".equals(relation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupe: seule relation member");
        }
        if (!"group".equals(objectType) && "member".equals(relation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "member réservé aux groupes");
        }
        if ("group".equals(subjectType) && "member".equals(relation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "member ne prend que subjectType=user");
        }
    }

    private static String subjectKey(String subjectType, UUID subjectId, String relation) {
        if ("group".equals(subjectType)) {
            return "group:" + subjectId + "#member";
        }
        return user(subjectId);
    }

    private boolean check(String user, String relation, String object) {
        try {
            var response = openFgaClient.check(new ClientCheckRequest()
                            .user(user)
                            .relation(relation)
                            ._object(object))
                    .join();
            return Boolean.TRUE.equals(response.getAllowed());
        } catch (Exception e) {
            log.error("OpenFGA check failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenFGA indisponible");
        }
    }

    /**
     * Write OpenFGA atomique (writes + deletes). En cas de conflit already-exists / not-found,
     * recalcule le diff depuis les tuples réels et réessaie une fois.
     */
    void writeAtomic(List<ClientTupleKey> writes, List<ClientTupleKeyWithoutCondition> deletes) {
        List<ClientTupleKey> w = writes == null ? List.of() : writes;
        List<ClientTupleKeyWithoutCondition> d = deletes == null ? List.of() : deletes;
        if (w.isEmpty() && d.isEmpty()) {
            return;
        }
        try {
            doWrite(w, d);
        } catch (RuntimeException e) {
            if (!isAlreadyExists(e) && !isNotFound(e)) {
                log.error("OpenFGA write failed", e);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenFGA write failed", e);
            }
            log.debug("OpenFGA write conflit — recalcul du diff et nouvel essai", e);
            DiffRetry retry = reconcileDiff(w, d);
            if (retry.writes().isEmpty() && retry.deletes().isEmpty()) {
                return;
            }
            try {
                doWrite(retry.writes(), retry.deletes());
            } catch (RuntimeException e2) {
                if (isAlreadyExists(e2) || isNotFound(e2)) {
                    log.debug("OpenFGA write encore en conflit après reconcile — ignoré (idempotent)");
                    return;
                }
                log.error("OpenFGA write failed after reconcile", e2);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenFGA write failed", e2);
            }
        }
    }

    private void doWrite(List<ClientTupleKey> writes, List<ClientTupleKeyWithoutCondition> deletes) {
        try {
            ClientWriteRequest req = new ClientWriteRequest();
            if (!writes.isEmpty()) {
                req = req.writes(writes);
            }
            if (!deletes.isEmpty()) {
                req = req.deletes(deletes);
            }
            // SDK 0.10 : mode transactionnel par défaut (une seule Write atomique).
            // On force transactions(true) pour documenter l'intention — pas disableTransactions.
            openFgaClient.write(req, new ClientWriteOptions().transactions(true)).join();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DiffRetry reconcileDiff(
            List<ClientTupleKey> wantedWrites,
            List<ClientTupleKeyWithoutCondition> wantedDeletes
    ) {
        Set<String> objects = new LinkedHashSet<>();
        wantedWrites.forEach(t -> objects.add(t.getObject()));
        wantedDeletes.forEach(t -> objects.add(t.getObject()));
        Set<String> existing = new HashSet<>();
        for (String obj : objects) {
            for (AccessTuple t : readTuples(obj)) {
                existing.add(key(t.user(), t.relation(), t.object()));
            }
        }
        List<ClientTupleKey> writes = new ArrayList<>();
        for (ClientTupleKey t : wantedWrites) {
            if (!existing.contains(key(t.getUser(), t.getRelation(), t.getObject()))) {
                writes.add(t);
            }
        }
        List<ClientTupleKeyWithoutCondition> deletes = new ArrayList<>();
        for (ClientTupleKeyWithoutCondition t : wantedDeletes) {
            if (existing.contains(key(t.getUser(), t.getRelation(), t.getObject()))) {
                deletes.add(t);
            }
        }
        return new DiffRetry(writes, deletes);
    }

    private void writeTuple(String user, String relation, String object) {
        writeAtomic(List.of(tuple(user, relation, object)), List.of());
    }

    private void deleteTuple(String user, String relation, String object) {
        writeAtomic(List.of(), List.of(deleteKey(user, relation, object)));
    }

    private static ClientTupleKey tuple(String user, String relation, String object) {
        return new ClientTupleKey().user(user).relation(relation)._object(object);
    }

    private static ClientTupleKeyWithoutCondition deleteKey(String user, String relation, String object) {
        return new ClientTupleKeyWithoutCondition().user(user).relation(relation)._object(object);
    }

    private static String key(String user, String relation, String object) {
        return user + "|" + relation + "|" + object;
    }

    private static boolean isAlreadyExists(Throwable e) {
        String msg = flattenMessage(e);
        return msg.contains("already exists") || msg.contains("cannot write a tuple which already exists");
    }

    /**
     * Conflit idempotent de <em>suppression</em> de tuple uniquement.
     * Ne pas confondre avec « type/relation not found » (échec dur, rollback transactionnel).
     */
    private static boolean isNotFound(Throwable e) {
        String msg = flattenMessage(e);
        if (msg.contains("type") && msg.contains("not found")) {
            return false;
        }
        if (msg.contains("relation") && msg.contains("undefined")) {
            return false;
        }
        if (msg.contains("relation") && msg.contains("not found") && !msg.contains("tuple")) {
            return false;
        }
        return msg.contains("cannot delete a tuple which does not exist")
                || msg.contains("tuple to be deleted was not found")
                || (msg.contains("tuple") && msg.contains("does not exist"));
    }

    private static String flattenMessage(Throwable e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        Throwable c = e.getCause();
        while (c != null) {
            msg += " " + String.valueOf(c.getMessage()).toLowerCase(Locale.ROOT);
            c = c.getCause();
        }
        return msg;
    }

    private static String classifyDirect(String fgaUser) {
        if (fgaUser != null && fgaUser.startsWith("group:") && fgaUser.contains("#member")) {
            return "group";
        }
        return "direct";
    }

    private static AccessEntry toEntry(AccessTuple t, String source, String inheritedFrom, boolean revocable) {
        ParsedSubject parsed = parseSubject(t.user());
        return new AccessEntry(
                t.user(),
                parsed.subjectType(),
                parsed.subjectId(),
                t.relation(),
                source,
                inheritedFrom,
                revocable && !STRUCTURAL_RELATIONS.contains(t.relation())
        );
    }

    static ParsedSubject parseSubject(String fgaUser) {
        if (fgaUser == null) {
            return new ParsedSubject("unknown", null);
        }
        if (fgaUser.startsWith("group:") && fgaUser.contains("#")) {
            String id = fgaUser.substring("group:".length(), fgaUser.indexOf('#'));
            try {
                return new ParsedSubject("group", UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                return new ParsedSubject("group", null);
            }
        }
        if (fgaUser.startsWith("user:")) {
            String id = fgaUser.substring("user:".length());
            if ("*".equals(id)) {
                return new ParsedSubject("wildcard", null);
            }
            try {
                return new ParsedSubject("user", UUID.fromString(id));
            } catch (IllegalArgumentException e) {
                return new ParsedSubject("user", null);
            }
        }
        return new ParsedSubject("unknown", null);
    }

    private static String user(UUID id) {
        return "user:" + id;
    }

    private static String document(UUID id) {
        return "document:" + id;
    }

    private static String space(UUID id) {
        return "space:" + id;
    }

    private static String folder(UUID id) {
        return "folder:" + id;
    }

    public record AccessTuple(String user, String relation, String object) {}

    public record AccessEntry(
            String subject,
            String subjectType,
            UUID subjectId,
            String relation,
            String source,
            String inheritedFrom,
            boolean revocable
    ) {}

    public record ParsedSubject(String subjectType, UUID subjectId) {}

    public record ReadableScope(
            List<UUID> spaceViewerIds,
            List<UUID> spaceOwnerIds,
            List<UUID> directDocumentIds,
            List<UUID> folderViewerIds
    ) {
        public ReadableScope {
            spaceViewerIds = spaceViewerIds == null ? List.of() : List.copyOf(spaceViewerIds);
            spaceOwnerIds = spaceOwnerIds == null ? List.of() : List.copyOf(spaceOwnerIds);
            directDocumentIds = directDocumentIds == null ? List.of() : List.copyOf(directDocumentIds);
            folderViewerIds = folderViewerIds == null ? List.of() : List.copyOf(folderViewerIds);
        }
    }

    private record DiffRetry(List<ClientTupleKey> writes, List<ClientTupleKeyWithoutCondition> deletes) {}
}
