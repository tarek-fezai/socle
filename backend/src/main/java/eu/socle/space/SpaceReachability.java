// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.space;

import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Un espace est atteignable si l'utilisateur est {@code space#viewer}, propriétaire en table,
 * lecteur d'au moins un document de l'espace, ou {@code viewer} d'au moins un dossier de l'espace.
 */
@Service
public class SpaceReachability {

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorizationService;

    public SpaceReachability(JdbcTemplate jdbc, AuthorizationService authorizationService) {
        this.jdbc = jdbc;
        this.authorizationService = authorizationService;
    }

    public boolean reachable(UUID userId, UUID spaceId) {
        if (userId == null || spaceId == null) {
            return false;
        }
        if (authorizationService.hasRelation(userId, "space", spaceId, "viewer")) {
            return true;
        }
        if (tableOwner(spaceId, userId)) {
            return true;
        }
        List<UUID> readable = authorizationService.listViewableDocumentIds(
                userId, DocumentScope.space(spaceId));
        if (readable != null && !readable.isEmpty()) {
            return true;
        }
        return !viewerFolderIds(userId, spaceId).isEmpty();
    }

    /**
     * Forme groupée du quatrième terme, pour {@link SpaceService#list}.
     * Les identifiants viennent du même {@code readableScope} que les documents
     * en accès direct, pour ne pas relancer ListObjects.
     */
    public Set<UUID> spaceIdsOfFolderViewers(List<UUID> folderIds) {
        if (folderIds == null || folderIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", folderIds.stream().map(id -> "?").toList());
        Set<UUID> out = new HashSet<>();
        jdbc.query(
                ("SELECT DISTINCT space_id FROM folders WHERE deleted_at IS NULL AND id IN (%s)"
                        .formatted(placeholders)),
                rs -> {
                    while (rs.next()) {
                        out.add((UUID) rs.getObject("space_id"));
                    }
                    return null;
                },
                folderIds.toArray());
        return out;
    }

    private List<UUID> viewerFolderIds(UUID userId, UUID spaceId) {
        List<UUID> folderIds = jdbc.query("""
                /* space-reachability-folders */
                SELECT id FROM folders WHERE space_id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> (UUID) rs.getObject("id"),
                spaceId);
        if (folderIds == null || folderIds.isEmpty()) {
            return List.of();
        }
        Collection<UUID> allowed = authorizationService.filterByFolderViewer(userId, folderIds);
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        return List.copyOf(allowed);
    }

    private boolean tableOwner(UUID spaceId, UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM space_owners WHERE space_id = ? AND user_id = ?",
                Integer.class, spaceId, userId);
        return n != null && n > 0;
    }
}
