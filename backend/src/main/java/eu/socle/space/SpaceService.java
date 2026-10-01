// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.space;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.authz.DocumentScope;
import eu.socle.document.DocumentVisibility;
import eu.socle.space.SpaceDtos.AddOwnerRequest;
import eu.socle.space.SpaceDtos.CreateSpaceRequest;
import eu.socle.space.SpaceDtos.GovernanceView;
import eu.socle.space.SpaceDtos.OwnerView;
import eu.socle.space.SpaceDtos.SpaceView;
import eu.socle.space.SpaceDtos.UpdateSpaceRequest;
import eu.socle.space.ExternalReferencePolicy;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD espaces + gouvernance {@code space_owners} (responsible ⊂ owners).
 *
 * <p>OpenFGA reste la source pour Check d'accès. {@code space_owners} matérialise
 * qui est owner / responsible pour la gouvernance (qui entre/sort des owners).
 */
@Service
public class SpaceService {

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public SpaceService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<SpaceView> list(Jwt jwt) {
        var user = userSyncService.syncFromJwt(jwt);
        Set<UUID> memberSpaces = new HashSet<>(authorizationService.listViewableSpaceIds(user.getId()));
        // Espaces dont on est owner en table (au cas où FGA listObjects est partiel)
        jdbc.query(
                "SELECT space_id FROM space_owners WHERE user_id = ?",
                rs -> {
                    while (rs.next()) {
                        memberSpaces.add((UUID) rs.getObject("space_id"));
                    }
                    return null;
                },
                user.getId());

        Set<UUID> publicOnlySpaces = spacesReachableViaReadableDocuments(user.getId());
        publicOnlySpaces.removeAll(memberSpaces);

        Set<UUID> visible = new HashSet<>(memberSpaces);
        visible.addAll(publicOnlySpaces);

        if (visible.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", visible.stream().map(id -> "?").toList());
        Object[] args = visible.toArray();
        return jdbc.query("""
                SELECT id, name, color, created_at, external_reference, default_visibility
                  FROM spaces
                 WHERE deleted_at IS NULL
                   AND id IN (%s)
                 ORDER BY name ASC
                """.formatted(placeholders),
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String membership = memberSpaces.contains(id) ? "member" : "public-only";
                    return toView(id,
                            rs.getString("name"),
                            rs.getString("color"),
                            rs.getTimestamp("created_at"),
                            rs.getString("external_reference"),
                            rs.getString("default_visibility"),
                            user.getId(),
                            membership);
                },
                args);
    }

    @Transactional(readOnly = true)
    public SpaceView get(Jwt jwt, UUID spaceId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireActive(spaceId);
        boolean member = authorizationService.hasRelation(user.getId(), "space", spaceId, "viewer")
                || isTableOwner(spaceId, user.getId());
        if (member) {
            return loadView(spaceId, user.getId(), "member");
        }
        // Non-membre : accessible s'il peut lire au moins un document (pages organisation, etc.)
        List<UUID> readable = authorizationService.listViewableDocumentIds(
                user.getId(), DocumentScope.space(spaceId));
        if (readable.isEmpty()) {
            // 404 : on ne révèle pas l'existence de l'espace
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
        }
        return loadView(spaceId, user.getId(), "public-only");
    }

    @Transactional
    public SpaceView create(Jwt jwt, CreateSpaceRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        UUID id = UUID.randomUUID();
        String color = blankToNull(request.color());
        jdbc.update(
                "INSERT INTO spaces (id, name, color, external_reference, default_visibility, created_at) "
                        + "VALUES (?, ?, ?, 'open', 'organisation', now())",
                id, request.name().trim(), color);
        jdbc.update("""
                INSERT INTO space_owners (space_id, user_id, is_responsible, created_at)
                VALUES (?, ?, true, now())
                """, id, user.getId());
        authorizationService.grantPermission("space", id, "owner", "user", user.getId());
        auditService.record(
                user.getId(), false, AuditActions.SPACE_CREATED, "space", id,
                Map.of("name", request.name().trim()), null);
        return loadView(id, user.getId());
    }

    @Transactional
    public SpaceView update(Jwt jwt, UUID spaceId, UpdateSpaceRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        requireActive(spaceId);
        requireOwner(spaceId, user.getId());

        String extRef = blankToNull(request.externalReference());
        if (extRef != null && !ExternalReferencePolicy.isValidMode(extRef)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "externalReference doit être open ou restricted");
        }
        String defaultVis = blankToNull(request.defaultVisibility());
        if (defaultVis != null) {
            defaultVis = DocumentVisibility.requireValid(defaultVis);
        }

        if (extRef != null && defaultVis != null) {
            jdbc.update(
                    "UPDATE spaces SET name = ?, color = ?, external_reference = ?, default_visibility = ? "
                            + "WHERE id = ? AND deleted_at IS NULL",
                    request.name().trim(),
                    blankToNull(request.color()),
                    extRef,
                    defaultVis,
                    spaceId);
        } else if (extRef != null) {
            jdbc.update(
                    "UPDATE spaces SET name = ?, color = ?, external_reference = ? WHERE id = ? AND deleted_at IS NULL",
                    request.name().trim(),
                    blankToNull(request.color()),
                    extRef,
                    spaceId);
        } else if (defaultVis != null) {
            jdbc.update(
                    "UPDATE spaces SET name = ?, color = ?, default_visibility = ? WHERE id = ? AND deleted_at IS NULL",
                    request.name().trim(),
                    blankToNull(request.color()),
                    defaultVis,
                    spaceId);
        } else {
            jdbc.update(
                    "UPDATE spaces SET name = ?, color = ? WHERE id = ? AND deleted_at IS NULL",
                    request.name().trim(),
                    blankToNull(request.color()),
                    spaceId);
        }
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("name", request.name().trim());
        if (extRef != null) {
            meta.put("externalReference", extRef);
        }
        if (defaultVis != null) {
            meta.put("defaultVisibility", defaultVis);
        }
        auditService.record(
                user.getId(), false, AuditActions.SPACE_UPDATED, "space", spaceId, meta, null);
        return loadView(spaceId, user.getId());
    }

    @Transactional(readOnly = true)
    public GovernanceView governance(Jwt jwt, UUID spaceId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireActive(spaceId);
        requireOwner(spaceId, user.getId());
        return new GovernanceView(spaceId, listOwners(spaceId));
    }

    @Transactional
    public GovernanceView addOwner(Jwt jwt, UUID spaceId, AddOwnerRequest request) {
        var actor = userSyncService.syncFromJwt(jwt);
        requireActive(spaceId);
        requireResponsible(spaceId, actor.getId());
        requireUserExists(request.userId());

        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM space_owners WHERE space_id = ? AND user_id = ?",
                Integer.class, spaceId, request.userId());
        if (exists != null && exists > 0) {
            if (request.responsible()) {
                jdbc.update(
                        "UPDATE space_owners SET is_responsible = true WHERE space_id = ? AND user_id = ?",
                        spaceId, request.userId());
            }
        } else {
            jdbc.update("""
                    INSERT INTO space_owners (space_id, user_id, is_responsible, created_at)
                    VALUES (?, ?, ?, now())
                    """, spaceId, request.userId(), request.responsible());
        }
        authorizationService.grantPermission("space", spaceId, "owner", "user", request.userId());
        auditService.record(
                actor.getId(), false, AuditActions.SPACE_OWNER_ADDED, "space", spaceId,
                Map.of("ownerUserId", request.userId().toString(), "responsible", request.responsible()),
                null);
        return new GovernanceView(spaceId, listOwners(spaceId));
    }

    @Transactional
    public GovernanceView removeOwner(Jwt jwt, UUID spaceId, UUID ownerUserId) {
        var actor = userSyncService.syncFromJwt(jwt);
        requireActive(spaceId);
        requireResponsible(spaceId, actor.getId());

        Boolean wasResponsible = jdbc.query(
                "SELECT is_responsible FROM space_owners WHERE space_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getBoolean("is_responsible") : null,
                spaceId, ownerUserId);
        if (wasResponsible == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner introuvable sur cet espace");
        }
        if (Boolean.TRUE.equals(wasResponsible)) {
            Integer responsibles = jdbc.queryForObject(
                    "SELECT count(*) FROM space_owners WHERE space_id = ? AND is_responsible = true",
                    Integer.class, spaceId);
            if (responsibles != null && responsibles <= 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Impossible de retirer le dernier responsible de l'espace");
            }
        }
        jdbc.update("DELETE FROM space_owners WHERE space_id = ? AND user_id = ?", spaceId, ownerUserId);
        authorizationService.revokePermission("space", spaceId, "owner", "user", ownerUserId);
        auditService.record(
                actor.getId(), false, AuditActions.SPACE_OWNER_REMOVED, "space", spaceId,
                Map.of("ownerUserId", ownerUserId.toString()), null);
        return new GovernanceView(spaceId, listOwners(spaceId));
    }

    @Transactional
    public GovernanceView setResponsible(Jwt jwt, UUID spaceId, UUID ownerUserId, boolean responsible) {
        var actor = userSyncService.syncFromJwt(jwt);
        requireActive(spaceId);
        requireResponsible(spaceId, actor.getId());

        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM space_owners WHERE space_id = ? AND user_id = ?",
                Integer.class, spaceId, ownerUserId);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner introuvable sur cet espace");
        }
        if (!responsible) {
            Integer responsibles = jdbc.queryForObject(
                    "SELECT count(*) FROM space_owners WHERE space_id = ? AND is_responsible = true",
                    Integer.class, spaceId);
            Boolean currently = jdbc.query(
                    "SELECT is_responsible FROM space_owners WHERE space_id = ? AND user_id = ?",
                    rs -> rs.next() && rs.getBoolean("is_responsible"),
                    spaceId, ownerUserId);
            if (Boolean.TRUE.equals(currently) && responsibles != null && responsibles <= 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Impossible de retirer le dernier responsible de l'espace");
            }
        }
        jdbc.update(
                "UPDATE space_owners SET is_responsible = ? WHERE space_id = ? AND user_id = ?",
                responsible, spaceId, ownerUserId);
        auditService.record(
                actor.getId(), false, AuditActions.SPACE_RESPONSIBLE_CHANGED, "space", spaceId,
                Map.of("ownerUserId", ownerUserId.toString(), "responsible", responsible), null);
        return new GovernanceView(spaceId, listOwners(spaceId));
    }

    /** Vérifie que l'espace existe et n'est pas soft-deleted. */
    public void requireActive(UUID spaceId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM spaces WHERE id = ? AND deleted_at IS NULL",
                Integer.class, spaceId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
        }
    }

    private void requireOwner(UUID spaceId, UUID userId) {
        bootstrapIfNeeded(spaceId, userId);
        if (!isTableOwner(spaceId, userId)
                && !authorizationService.hasRelation(userId, "space", spaceId, "owner")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Seul un owner peut gérer cet espace");
        }
    }

    private void requireResponsible(UUID spaceId, UUID userId) {
        bootstrapIfNeeded(spaceId, userId);
        Boolean ok = jdbc.query(
                "SELECT is_responsible FROM space_owners WHERE space_id = ? AND user_id = ?",
                rs -> rs.next() && rs.getBoolean("is_responsible"),
                spaceId, userId);
        if (!Boolean.TRUE.equals(ok)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Seul un responsible peut gérer les owners de l'espace");
        }
    }

    /**
     * Espaces seedés / legacy : si aucun row space_owners mais l'acteur est déjà
     * owner OpenFGA, l'enregistrer comme premier responsible.
     */
    private void bootstrapIfNeeded(UUID spaceId, UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM space_owners WHERE space_id = ?", Integer.class, spaceId);
        if (count != null && count > 0) {
            return;
        }
        if (!authorizationService.hasRelation(userId, "space", spaceId, "owner")) {
            return;
        }
        jdbc.update("""
                INSERT INTO space_owners (space_id, user_id, is_responsible, created_at)
                VALUES (?, ?, true, now())
                ON CONFLICT (space_id, user_id) DO NOTHING
                """, spaceId, userId);
    }

    private boolean isTableOwner(UUID spaceId, UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM space_owners WHERE space_id = ? AND user_id = ?",
                Integer.class, spaceId, userId);
        return n != null && n > 0;
    }

    private boolean isResponsible(UUID spaceId, UUID userId) {
        Boolean ok = jdbc.query(
                "SELECT is_responsible FROM space_owners WHERE space_id = ? AND user_id = ?",
                rs -> rs.next() && rs.getBoolean("is_responsible"),
                spaceId, userId);
        return Boolean.TRUE.equals(ok);
    }

    private List<OwnerView> listOwners(UUID spaceId) {
        return jdbc.query("""
                SELECT so.user_id, so.is_responsible, u.email, u.display_name
                  FROM space_owners so
                  JOIN users u ON u.id = so.user_id
                 WHERE so.space_id = ?
                 ORDER BY so.is_responsible DESC, u.display_name ASC
                """,
                (rs, i) -> new OwnerView(
                        (UUID) rs.getObject("user_id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getBoolean("is_responsible")),
                spaceId);
    }

    private SpaceView loadView(UUID spaceId, UUID userId) {
        return loadView(spaceId, userId, "member");
    }

    private SpaceView loadView(UUID spaceId, UUID userId, String membership) {
        return jdbc.query("""
                SELECT id, name, color, created_at, external_reference, default_visibility FROM spaces
                 WHERE id = ? AND deleted_at IS NULL
                """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Espace introuvable");
                    }
                    return toView(
                            (UUID) rs.getObject("id"),
                            rs.getString("name"),
                            rs.getString("color"),
                            rs.getTimestamp("created_at"),
                            rs.getString("external_reference"),
                            rs.getString("default_visibility"),
                            userId,
                            membership);
                },
                spaceId);
    }

    /**
     * Espaces contenant au moins un document potentiellement lisible sans membership
     * espace : pages {@code organisation}, docs en {@code direct_access}, ou dossiers
     * {@code viewer} hors appartenance espace.
     */
    private Set<UUID> spacesReachableViaReadableDocuments(UUID userId) {
        Set<UUID> out = new HashSet<>();
        jdbc.query(
                """
                SELECT DISTINCT space_id FROM documents
                 WHERE deleted_at IS NULL AND visibility = 'organisation'
                """,
                rs -> {
                    while (rs.next()) {
                        out.add((UUID) rs.getObject("space_id"));
                    }
                    return null;
                });

        var readable = authorizationService.readableScope(userId);
        if (!readable.directDocumentIds().isEmpty()) {
            String placeholders = String.join(",",
                    readable.directDocumentIds().stream().map(id -> "?").toList());
            List<Object> args = new ArrayList<>(readable.directDocumentIds());
            jdbc.query(
                    ("SELECT DISTINCT space_id FROM documents WHERE deleted_at IS NULL AND id IN (%s)"
                            .formatted(placeholders)),
                    rs -> {
                        while (rs.next()) {
                            out.add((UUID) rs.getObject("space_id"));
                        }
                        return null;
                    },
                    args.toArray());
        }
        if (!readable.folderViewerIds().isEmpty()) {
            String placeholders = String.join(",",
                    readable.folderViewerIds().stream().map(id -> "?").toList());
            List<Object> args = new ArrayList<>(readable.folderViewerIds());
            jdbc.query(
                    ("SELECT DISTINCT space_id FROM folders WHERE deleted_at IS NULL AND id IN (%s)"
                            .formatted(placeholders)),
                    rs -> {
                        while (rs.next()) {
                            out.add((UUID) rs.getObject("space_id"));
                        }
                        return null;
                    },
                    args.toArray());
        }
        return out;
    }

    private SpaceView toView(
            UUID id,
            String name,
            String color,
            Timestamp createdAt,
            String externalReference,
            String defaultVisibility,
            UUID userId,
            String membership
    ) {
        boolean owner = isTableOwner(id, userId)
                || authorizationService.hasRelation(userId, "space", id, "owner");
        boolean responsible = isResponsible(id, userId);
        String mode = externalReference == null || externalReference.isBlank()
                ? ExternalReferencePolicy.OPEN
                : externalReference;
        String dv = defaultVisibility == null || defaultVisibility.isBlank()
                ? DocumentVisibility.ORGANISATION
                : defaultVisibility;
        return new SpaceView(
                id,
                name,
                color,
                createdAt != null ? createdAt.toInstant().toString() : null,
                mode,
                dv,
                owner,
                owner,
                responsible,
                membership == null ? "member" : membership);
    }

    private void requireUserExists(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId introuvable");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
