// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.favorites;

import eu.socle.authz.AuthorizationService;
import eu.socle.favorites.FavoriteDtos.FavoriteItem;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FavoriteService {

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final Clock clock;

    public FavoriteService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.clock = clock;
    }

    @Transactional
    public FavoriteItem put(Jwt jwt, FavoriteTargetType type, UUID targetId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(type, targetId);
        requireCanView(user.getId(), type, targetId);

        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO favorites (user_id, target_type, target_id, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, target_type, target_id) DO NOTHING
                """,
                user.getId(), type.wire(), targetId, Timestamp.from(now));

        Instant created = jdbc.query("""
                SELECT created_at FROM favorites
                 WHERE user_id = ? AND target_type = ? AND target_id = ?
                """,
                rs -> rs.next() ? rs.getTimestamp("created_at").toInstant() : now,
                user.getId(), type.wire(), targetId);

        return hydrateOne(type, targetId, created);
    }

    @Transactional
    public void delete(Jwt jwt, FavoriteTargetType type, UUID targetId) {
        var user = userSyncService.syncFromJwt(jwt);
        jdbc.update("""
                DELETE FROM favorites
                 WHERE user_id = ? AND target_type = ? AND target_id = ?
                """,
                user.getId(), type.wire(), targetId);
    }

    /**
     * Liste les favoris encore visibles OpenFGA. Les lignes inaccessibles sont
     * omises (pas de DELETE silencieux).
     */
    @Transactional(readOnly = true)
    public List<FavoriteItem> list(Jwt jwt) {
        var user = userSyncService.syncFromJwt(jwt);
        List<Row> rows = jdbc.query("""
                SELECT target_type, target_id, created_at
                  FROM favorites
                 WHERE user_id = ?
                 ORDER BY created_at DESC
                """,
                (rs, i) -> new Row(
                        rs.getString("target_type"),
                        (UUID) rs.getObject("target_id"),
                        rs.getTimestamp("created_at").toInstant()),
                user.getId());

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<String, List<UUID>> byType = new HashMap<>();
        for (Row r : rows) {
            byType.computeIfAbsent(r.type(), k -> new ArrayList<>()).add(r.id());
        }

        Set<UUID> okDocs = new HashSet<>(authorizationService.filterByDocumentViewer(
                user.getId(), byType.getOrDefault("document", List.of()), "favorites"));
        Set<UUID> okFolders = new HashSet<>(authorizationService.filterByFolderViewer(
                user.getId(), byType.getOrDefault("folder", List.of())));
        Set<UUID> okSpaces = new HashSet<>(authorizationService.filterBySpaceViewer(
                user.getId(), byType.getOrDefault("space", List.of())));

        Map<UUID, DocMeta> docs = loadDocuments(okDocs);
        Map<UUID, FolderMeta> folders = loadFolders(okFolders);
        Map<UUID, SpaceMeta> spaces = loadSpaces(okSpaces);
        Map<UUID, Integer> spaceDocCounts = visibleDocumentCounts(user.getId(), new HashSet<>(okSpaces));

        List<FavoriteItem> out = new ArrayList<>();
        for (Row r : rows) {
            boolean ok = switch (r.type()) {
                case "document" -> okDocs.contains(r.id());
                case "folder" -> okFolders.contains(r.id());
                case "space" -> okSpaces.contains(r.id());
                default -> false;
            };
            if (!ok) {
                // Favori conservé sans métadonnées — ni titre, ni statut, ni espace.
                out.add(new FavoriteItem(r.type(), r.id(), r.createdAt(), null, null, null, null, null, null));
                continue;
            }
            out.add(toItem(r, docs, folders, spaces, spaceDocCounts));
        }
        return out;
    }

    private FavoriteItem hydrateOne(FavoriteTargetType type, UUID targetId, Instant created) {
        return switch (type) {
            case DOCUMENT -> {
                Map<UUID, DocMeta> docs = loadDocuments(Set.of(targetId));
                DocMeta m = docs.get(targetId);
                yield m == null
                        ? new FavoriteItem(type.wire(), targetId, created, null)
                        : new FavoriteItem(type.wire(), targetId, created, m.title, m.status,
                                m.updatedAt, m.spaceId, m.spaceName, null);
            }
            case FOLDER -> {
                Map<UUID, FolderMeta> folders = loadFolders(Set.of(targetId));
                FolderMeta m = folders.get(targetId);
                yield m == null
                        ? new FavoriteItem(type.wire(), targetId, created, null)
                        : new FavoriteItem(type.wire(), targetId, created, m.name, null,
                                null, m.spaceId, m.spaceName, null);
            }
            case SPACE -> {
                Map<UUID, SpaceMeta> spaces = loadSpaces(Set.of(targetId));
                SpaceMeta m = spaces.get(targetId);
                yield m == null
                        ? new FavoriteItem(type.wire(), targetId, created, null)
                        : new FavoriteItem(type.wire(), targetId, created, m.name, null,
                                null, targetId, m.name, null);
            }
        };
    }

    private FavoriteItem toItem(
            Row r,
            Map<UUID, DocMeta> docs,
            Map<UUID, FolderMeta> folders,
            Map<UUID, SpaceMeta> spaces,
            Map<UUID, Integer> spaceDocCounts
    ) {
        return switch (r.type()) {
            case "document" -> {
                DocMeta m = docs.get(r.id());
                yield m == null
                        ? new FavoriteItem(r.type(), r.id(), r.createdAt(), null)
                        : new FavoriteItem(r.type(), r.id(), r.createdAt(), m.title, m.status,
                                m.updatedAt, m.spaceId, m.spaceName, null);
            }
            case "folder" -> {
                FolderMeta m = folders.get(r.id());
                yield m == null
                        ? new FavoriteItem(r.type(), r.id(), r.createdAt(), null)
                        : new FavoriteItem(r.type(), r.id(), r.createdAt(), m.name, null,
                                null, m.spaceId, m.spaceName, null);
            }
            case "space" -> {
                SpaceMeta m = spaces.get(r.id());
                yield m == null
                        ? new FavoriteItem(r.type(), r.id(), r.createdAt(), null)
                        : new FavoriteItem(r.type(), r.id(), r.createdAt(), m.name, null,
                                null, r.id(), m.name, spaceDocCounts.getOrDefault(r.id(), 0));
            }
            default -> new FavoriteItem(r.type(), r.id(), r.createdAt(), null);
        };
    }

    private Map<UUID, DocMeta> loadDocuments(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Map<UUID, DocMeta> out = new HashMap<>();
        jdbc.query(
                ("""
                SELECT d.id, d.title, d.status, d.updated_at, d.space_id, s.name AS space_name
                  FROM documents d
                  JOIN spaces s ON s.id = d.space_id
                 WHERE d.deleted_at IS NULL AND d.id IN (%s)
                """.formatted(placeholders)),
                rs -> {
                    while (rs.next()) {
                        Timestamp ts = rs.getTimestamp("updated_at");
                        out.put((UUID) rs.getObject("id"), new DocMeta(
                                rs.getString("title"),
                                rs.getString("status"),
                                ts == null ? null : ts.toInstant(),
                                (UUID) rs.getObject("space_id"),
                                rs.getString("space_name")));
                    }
                    return null;
                },
                ids.toArray());
        return out;
    }

    private Map<UUID, FolderMeta> loadFolders(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Map<UUID, FolderMeta> out = new HashMap<>();
        jdbc.query(
                ("""
                SELECT f.id, f.name, f.space_id, s.name AS space_name
                  FROM folders f
                  JOIN spaces s ON s.id = f.space_id
                 WHERE f.deleted_at IS NULL AND f.id IN (%s)
                """.formatted(placeholders)),
                rs -> {
                    while (rs.next()) {
                        out.put((UUID) rs.getObject("id"), new FolderMeta(
                                rs.getString("name"),
                                (UUID) rs.getObject("space_id"),
                                rs.getString("space_name")));
                    }
                    return null;
                },
                ids.toArray());
        return out;
    }

    private Map<UUID, SpaceMeta> loadSpaces(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Map<UUID, SpaceMeta> out = new HashMap<>();
        jdbc.query(
                ("""
                SELECT id, name FROM spaces WHERE deleted_at IS NULL AND id IN (%s)
                """.formatted(placeholders)),
                rs -> {
                    while (rs.next()) {
                        out.put((UUID) rs.getObject("id"), new SpaceMeta(rs.getString("name")));
                    }
                    return null;
                },
                ids.toArray());
        return out;
    }

    private Map<UUID, Integer> visibleDocumentCounts(UUID userId, Set<UUID> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", spaceIds.stream().map(id -> "?").toList());
        List<UUID> docIds = new ArrayList<>();
        Map<UUID, UUID> docToSpace = new HashMap<>();
        jdbc.query(
                ("SELECT id, space_id FROM documents WHERE deleted_at IS NULL AND space_id IN (%s)"
                        .formatted(placeholders)),
                rs -> {
                    while (rs.next()) {
                        UUID docId = (UUID) rs.getObject("id");
                        docIds.add(docId);
                        docToSpace.put(docId, (UUID) rs.getObject("space_id"));
                    }
                    return null;
                },
                spaceIds.toArray());
        if (docIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> visible = authorizationService.filterByDocumentViewer(
                userId, docIds, "favorites-space-docs");
        Map<UUID, Integer> counts = new HashMap<>();
        for (UUID docId : visible) {
            UUID spaceId = docToSpace.get(docId);
            if (spaceId != null) {
                counts.merge(spaceId, 1, Integer::sum);
            }
        }
        return counts;
    }

    private void requireCanView(UUID userId, FavoriteTargetType type, UUID targetId) {
        boolean ok = switch (type) {
            case DOCUMENT -> authorizationService.hasRelation(userId, "document", targetId, "viewer");
            case SPACE -> authorizationService.hasRelation(userId, "space", targetId, "viewer");
            case FOLDER -> authorizationService.hasRelation(userId, "folder", targetId, "viewer");
        };
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé");
        }
    }

    private void requireExists(FavoriteTargetType type, UUID targetId) {
        Integer n = switch (type) {
            case DOCUMENT -> jdbc.queryForObject(
                    "SELECT count(*) FROM documents WHERE id = ? AND deleted_at IS NULL",
                    Integer.class, targetId);
            case SPACE -> jdbc.queryForObject(
                    "SELECT count(*) FROM spaces WHERE id = ? AND deleted_at IS NULL",
                    Integer.class, targetId);
            case FOLDER -> jdbc.queryForObject(
                    "SELECT count(*) FROM folders WHERE id = ? AND deleted_at IS NULL",
                    Integer.class, targetId);
        };
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cible introuvable");
        }
    }

    private record Row(String type, UUID id, Instant createdAt) {}

    private record DocMeta(String title, String status, Instant updatedAt, UUID spaceId, String spaceName) {}

    private record FolderMeta(String name, UUID spaceId, String spaceName) {}

    private record SpaceMeta(String name) {}
}
