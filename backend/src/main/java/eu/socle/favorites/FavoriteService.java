// SPDX-License-Identifier: AGPL-3.0-or-later
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

        return new FavoriteItem(type.wire(), targetId, created, resolveTitle(type, targetId));
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
        Set<UUID> okSpaces = filterViewableSpaces(
                user.getId(), byType.getOrDefault("space", List.of()));

        List<FavoriteItem> out = new ArrayList<>();
        for (Row r : rows) {
            boolean ok = switch (r.type()) {
                case "document" -> okDocs.contains(r.id());
                case "folder" -> okFolders.contains(r.id());
                case "space" -> okSpaces.contains(r.id());
                default -> false;
            };
            if (!ok) {
                continue;
            }
            out.add(new FavoriteItem(r.type(), r.id(), r.createdAt(), resolveTitle(
                    FavoriteTargetType.fromPath(r.type()), r.id())));
        }
        return out;
    }

    private Set<UUID> filterViewableSpaces(UUID userId, List<UUID> spaceIds) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> allowed = new HashSet<>();
        for (UUID id : spaceIds) {
            if (authorizationService.hasRelation(userId, "space", id, "viewer")) {
                allowed.add(id);
            }
        }
        return allowed;
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

    private String resolveTitle(FavoriteTargetType type, UUID targetId) {
        return switch (type) {
            case DOCUMENT -> jdbc.query(
                    "SELECT title FROM documents WHERE id = ? AND deleted_at IS NULL",
                    rs -> rs.next() ? rs.getString("title") : null,
                    targetId);
            case SPACE -> jdbc.query(
                    "SELECT name FROM spaces WHERE id = ? AND deleted_at IS NULL",
                    rs -> rs.next() ? rs.getString("name") : null,
                    targetId);
            case FOLDER -> jdbc.query(
                    "SELECT name FROM folders WHERE id = ? AND deleted_at IS NULL",
                    rs -> rs.next() ? rs.getString("name") : null,
                    targetId);
        };
    }

    private record Row(String type, UUID id, Instant createdAt) {}
}
