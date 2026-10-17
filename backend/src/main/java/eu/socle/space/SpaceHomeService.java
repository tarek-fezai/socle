// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import eu.socle.authz.AuthorizationService;
import eu.socle.document.DocumentDtos.TagRef;
import eu.socle.document.PageDocuments;
import eu.socle.folder.FolderDtos.FolderDocumentCard;
import eu.socle.folder.FolderDtos.OwnerGroupRef;
import eu.socle.folder.FolderDtos.OwnerPerson;
import eu.socle.folder.FolderDtos.StatusCounts;
import eu.socle.space.SpaceDtos.LastActivity;
import eu.socle.space.SpaceDtos.SpaceDetail;
import eu.socle.space.SpaceDtos.SpaceFolderCard;
import eu.socle.space.SpaceDtos.SpaceMemberPreview;
import eu.socle.space.SpaceDtos.SpaceView;
import eu.socle.user.UserSyncService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Charge utile de {@code GET /spaces/{id}} pour l'accueil d'espace.
 * Les inventaires excluent la page d'espace.
 */
@Service
public class SpaceHomeService {

    private static final int MEMBER_CAP = 4;

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final SpaceService spaceService;

    public SpaceHomeService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            SpaceService spaceService
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.spaceService = spaceService;
    }

    @Transactional(readOnly = true)
    public SpaceDetail get(Jwt jwt, UUID spaceId) {
        SpaceView view = spaceService.get(jwt, spaceId);
        var user = userSyncService.syncFromJwt(jwt);
        UUID userId = user.getId();
        boolean canEdit = authorizationService.hasRelation(userId, "space", spaceId, "editor");

        PageRow page = loadPage(spaceId);
        List<DocRow> docs = loadDocuments(spaceId);
        Set<UUID> visibleIds = new HashSet<>(authorizationService.filterByDocumentViewer(
                userId, docs.stream().map(DocRow::id).toList(), "space-home"));
        List<DocRow> visible = docs.stream().filter(d -> visibleIds.contains(d.id())).toList();
        Map<UUID, List<TagRef>> tagsByDoc = loadTags(visible.stream().map(DocRow::id).toList());

        List<FolderDocumentCard> rootDocuments = new ArrayList<>();
        List<FolderDocumentCard> toReview = new ArrayList<>();
        int validated = 0;
        int inReview = 0;
        int draft = 0;
        BigDecimal scoreSum = BigDecimal.ZERO;
        int scoreN = 0;
        for (DocRow doc : visible) {
            if ("valide".equals(doc.status())) {
                validated++;
            } else if ("en_revue".equals(doc.status())) {
                inReview++;
            } else if ("brouillon".equals(doc.status())) {
                draft++;
            }
            if (doc.reliability() != null) {
                scoreSum = scoreSum.add(doc.reliability());
                scoreN++;
            }
            FolderDocumentCard card = card(doc, tagsByDoc);
            if (doc.folderId() == null) {
                rootDocuments.add(card);
            }
            if ("en_revue".equals(doc.status())) {
                toReview.add(card);
            }
        }

        List<SpaceFolderCard> folders = folderCards(spaceId, userId, visible);
        LastActivity last = lastActivity(page, visible);
        Set<UUID> memberIds = authorizationService.listSpaceMemberIds(spaceId);
        List<SpaceMemberPreview> members = memberInitials(memberIds);

        return new SpaceDetail(
                view.id(),
                view.name(),
                view.color(),
                view.createdAt(),
                view.externalReference(),
                view.defaultVisibility(),
                view.commentPolicy(),
                view.canManage(),
                view.isOwner(),
                view.isResponsible(),
                view.membership(),
                view.description(),
                view.documentCount(),
                view.memberPreview(),
                view.memberExtraCount(),
                page == null ? null : page.id(),
                canEdit,
                canEdit,
                page == null ? null : page.language(),
                "actif",
                ownerTeam(spaceId),
                memberIds.size(),
                teamCount(spaceId),
                members,
                last,
                scoreN == 0 ? null : scoreSum.divide(BigDecimal.valueOf(scoreN), 2, RoundingMode.HALF_UP),
                new StatusCounts(validated, inReview, draft),
                folders,
                rootDocuments,
                toReview,
                unionTags(tagsByDoc));
    }

    private List<SpaceMemberPreview> memberInitials(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<NamedInitials> rows = jdbc.query(
                ("SELECT id, display_name, avatar_initials FROM users WHERE id IN (%s)"
                        .formatted(placeholders)),
                (rs, i) -> new NamedInitials(
                        (UUID) rs.getObject("id"),
                        rs.getString("display_name"),
                        rs.getString("avatar_initials")),
                ids.toArray());
        rows.sort(Comparator
                .comparing((NamedInitials r) -> r.name() == null ? "" : r.name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(NamedInitials::id));
        List<SpaceMemberPreview> out = new ArrayList<>();
        int cap = Math.min(MEMBER_CAP, rows.size());
        for (int i = 0; i < cap; i++) {
            NamedInitials row = rows.get(i);
            String initials = row.initials();
            if (initials == null || initials.isBlank()) {
                initials = eu.socle.document.DocumentDtos.PersonRef.initialsOf(row.name());
            }
            out.add(new SpaceMemberPreview(initials));
        }
        if (rows.size() <= MEMBER_CAP) {
            return out;
        }
        return out;
    }

    private int teamCount(UUID spaceId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT g.id)
                  FROM groups g
                  JOIN group_members gm ON gm.group_id = g.id
                  JOIN space_owners so ON so.user_id = gm.user_id AND so.space_id = ?
                """, Integer.class, spaceId);
        return n == null ? 0 : n;
    }

    private OwnerGroupRef ownerTeam(UUID spaceId) {
        List<OwnerGroupRef> rows = jdbc.query("""
                SELECT g.id, g.name
                  FROM groups g
                  JOIN group_members gm ON gm.group_id = g.id AND gm.role = 'proprietaire'
                  JOIN space_owners so ON so.user_id = gm.user_id AND so.space_id = ?
                 ORDER BY so.is_responsible DESC, g.created_at ASC, g.id
                 LIMIT 1
                """,
                (rs, i) -> new OwnerGroupRef((UUID) rs.getObject("id"), rs.getString("name")),
                spaceId);
        if (rows.isEmpty()) {
            return null;
        }
        OwnerGroupRef group = rows.getFirst();
        return new OwnerGroupRef(group.id(), group.name(), loadOwnerPerson(group.id()));
    }

    private OwnerPerson loadOwnerPerson(UUID groupId) {
        List<OwnerPerson> rows = jdbc.query("""
                SELECT u.id, u.display_name, u.avatar_initials
                  FROM group_members gm
                  JOIN groups g ON g.id = gm.group_id
                  JOIN users u ON u.id = gm.user_id
                  LEFT JOIN LATERAL (
                    SELECT MIN(e.created_at) AS added_at
                      FROM audit_log_events e
                     WHERE e.action = 'group.member_added'
                       AND e.resource_type = 'group'
                       AND e.resource_id = gm.group_id
                       AND e.metadata ->> 'memberUserId' = gm.user_id::text
                  ) added ON true
                 WHERE gm.group_id = ? AND gm.role = 'proprietaire'
                 ORDER BY COALESCE(added.added_at, CASE WHEN u.id = g.created_by THEN g.created_at END)
                          ASC NULLS LAST,
                          u.id
                 LIMIT 1
                """,
                (rs, i) -> {
                    String name = rs.getString("display_name");
                    String initials = rs.getString("avatar_initials");
                    if (initials == null || initials.isBlank()) {
                        initials = eu.socle.document.DocumentDtos.PersonRef.initialsOf(name);
                    }
                    return new OwnerPerson((UUID) rs.getObject("id"), name, initials);
                },
                groupId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<SpaceFolderCard> folderCards(UUID spaceId, UUID userId, List<DocRow> visible) {
        List<FolderHead> folders = jdbc.query("""
                SELECT id, name FROM folders
                 WHERE space_id = ? AND parent_folder_id IS NULL AND deleted_at IS NULL
                 ORDER BY position ASC, lower(name) ASC
                """,
                (rs, i) -> new FolderHead((UUID) rs.getObject("id"), rs.getString("name")),
                spaceId);
        Set<UUID> visibleFolders = new HashSet<>(authorizationService.filterByFolderViewer(
                userId, folders.stream().map(FolderHead::id).toList()));
        List<SpaceFolderCard> out = new ArrayList<>();
        for (FolderHead folder : folders) {
            if (!visibleFolders.contains(folder.id())) {
                continue;
            }
            List<DocRow> direct = visible.stream().filter(d -> folder.id().equals(d.folderId())).toList();
            int review = (int) direct.stream().filter(d -> "en_revue".equals(d.status())).count();
            out.add(new SpaceFolderCard(
                    folder.id(), folder.name(), direct.size(), review, average(direct)));
        }
        return out;
    }

    private static BigDecimal average(List<DocRow> docs) {
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (DocRow doc : docs) {
            if (doc.reliability() != null) {
                sum = sum.add(doc.reliability());
                n++;
            }
        }
        if (docs.isEmpty() || n == 0) {
            return docs.isEmpty() ? null : null;
        }
        return sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    private LastActivity lastActivity(PageRow page, List<DocRow> visible) {
        Instant at = page == null ? null : page.updatedAt();
        String who = page == null ? null : page.updatedBy();
        for (DocRow doc : visible) {
            if (doc.updatedAt() != null && (at == null || doc.updatedAt().isAfter(at))) {
                at = doc.updatedAt();
                who = doc.updatedBy();
            }
        }
        if (at == null) {
            return null;
        }
        return new LastActivity(who, at);
    }

    private PageRow loadPage(UUID spaceId) {
        List<PageRow> rows = jdbc.query("""
                SELECT d.id, d.language, d.updated_at, u.display_name
                  FROM spaces s
                  JOIN documents d ON d.id = s.page_document_id AND d.deleted_at IS NULL
                  LEFT JOIN users u ON u.id = d.updated_by
                 WHERE s.id = ?
                """,
                (rs, i) -> {
                    Timestamp updated = rs.getTimestamp("updated_at");
                    return new PageRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("language"),
                            updated == null ? null : updated.toInstant(),
                            rs.getString("display_name"));
                },
                spaceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<DocRow> loadDocuments(UUID spaceId) {
        return jdbc.query("""
                SELECT d.id, d.folder_id, d.title, d.status, d.doc_type, d.updated_at,
                       d.reliability_score, u.display_name AS updated_name,
                       (SELECT MIN(ar.created_at) FROM approval_requests ar
                         WHERE ar.document_id = d.id AND ar.status = 'en_cours') AS review_since
                  FROM documents d
                  LEFT JOIN users u ON u.id = d.updated_by
                 WHERE d.space_id = ? AND d.deleted_at IS NULL
                   AND %s
                 ORDER BY d.position ASC, lower(d.title) ASC
                """.formatted(PageDocuments.EXCLUDE_SQL),
                (rs, i) -> {
                    Timestamp updated = rs.getTimestamp("updated_at");
                    Timestamp review = rs.getTimestamp("review_since");
                    return new DocRow(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("folder_id"),
                            rs.getString("title"),
                            rs.getString("status"),
                            rs.getString("doc_type"),
                            updated == null ? null : updated.toInstant(),
                            rs.getString("updated_name"),
                            review == null ? null : review.toInstant(),
                            rs.getBigDecimal("reliability_score"));
                },
                spaceId);
    }

    private Map<UUID, List<TagRef>> loadTags(List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        Map<UUID, List<TagRef>> byDoc = new LinkedHashMap<>();
        jdbc.query("""
                SELECT dt.document_id, t.id, t.name, t.color,
                       EXISTS (SELECT 1 FROM approval_role_assignments ara
                                WHERE ara.scope_type = 'tag'
                                  AND lower(ara.scope_ref) = t.id::text) AS governed
                  FROM document_tags dt
                  JOIN tags t ON t.id = dt.tag_id
                 WHERE dt.document_id IN (""" + placeholders + """
                )
                 ORDER BY t.created_at ASC NULLS LAST, lower(t.name)
                """,
                rs -> {
                    UUID docId = (UUID) rs.getObject("document_id");
                    byDoc.computeIfAbsent(docId, id -> new ArrayList<>()).add(new TagRef(
                            (UUID) rs.getObject("id"),
                            rs.getString("name"),
                            rs.getString("color"),
                            rs.getBoolean("governed")));
                },
                documentIds.toArray());
        return byDoc;
    }

    private static List<TagRef> unionTags(Map<UUID, List<TagRef>> byDoc) {
        Map<UUID, TagRef> union = new LinkedHashMap<>();
        for (List<TagRef> tags : byDoc.values()) {
            for (TagRef tag : tags) {
                union.putIfAbsent(tag.id(), tag);
            }
        }
        return List.copyOf(union.values());
    }

    private static FolderDocumentCard card(DocRow doc, Map<UUID, List<TagRef>> tags) {
        return new FolderDocumentCard(
                doc.id(), doc.title(), doc.status(), doc.docType(),
                doc.updatedAt(), doc.updatedBy(), doc.reviewSince(),
                tags.getOrDefault(doc.id(), List.of()));
    }

    private record PageRow(UUID id, String language, Instant updatedAt, String updatedBy) {}

    private record DocRow(
            UUID id, UUID folderId, String title, String status, String docType,
            Instant updatedAt, String updatedBy, Instant reviewSince, BigDecimal reliability
    ) {}

    private record FolderHead(UUID id, String name) {}

    private record NamedInitials(UUID id, String name, String initials) {}
}
