package eu.socle.team;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AuthorizationService;
import eu.socle.identity.IdentityFacade;
import eu.socle.team.GroupDtos.AddMemberRequest;
import eu.socle.team.GroupDtos.CreateGroupRequest;
import eu.socle.team.GroupDtos.GroupView;
import eu.socle.team.GroupDtos.MemberView;
import eu.socle.team.GroupDtos.UpdateGroupRequest;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD groupes / membres.
 *
 * <p><b>Politique</b> (documentée aussi dans {@code docs/spaces-governance.md}) :
 * tout utilisateur authentifié peut créer un groupe ; seuls le créateur
 * ({@code groups.created_by}) ou un {@code ADMINISTRATEUR_SYSTEME}
 * peuvent renommer, supprimer, ajouter/retirer des membres.
 * L'attribution d'accès FGA à un groupe reste réservée aux owners de la ressource
 * (AccessController inchangé sur ce point).
 */
@Service
public class GroupService {

    private final JdbcTemplate jdbc;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final IdentityFacade identityFacade;

    public GroupService(
            JdbcTemplate jdbc,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            AuditService auditService,
            IdentityFacade identityFacade
    ) {
        this.jdbc = jdbc;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.identityFacade = identityFacade;
    }

    @Transactional(readOnly = true)
    public List<GroupView> list(Jwt jwt) {
        var user = userSyncService.syncFromJwt(jwt);
        boolean admin = isSystemAdmin(jwt);
        List<UUID> ids = jdbc.query(
                "SELECT id FROM groups ORDER BY name ASC",
                (rs, i) -> (UUID) rs.getObject("id"));
        return ids.stream().map(id -> loadView(id, user.getId(), admin)).toList();
    }

    @Transactional(readOnly = true)
    public GroupView get(Jwt jwt, UUID groupId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(groupId);
        return loadView(groupId, user.getId(), isSystemAdmin(jwt));
    }

    @Transactional
    public GroupView create(Jwt jwt, CreateGroupRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO groups (id, name, created_by, created_at) VALUES (?, ?, ?, now())",
                id, request.name().trim(), user.getId());
        auditService.record(
                user.getId(), false, AuditActions.GROUP_CREATED, "group", id,
                Map.of("name", request.name().trim()), null);
        return loadView(id, user.getId(), isSystemAdmin(jwt));
    }

    @Transactional
    public GroupView update(Jwt jwt, UUID groupId, UpdateGroupRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(groupId);
        requireCanManage(jwt, user.getId(), groupId);
        jdbc.update("UPDATE groups SET name = ? WHERE id = ?", request.name().trim(), groupId);
        auditService.record(
                user.getId(), false, AuditActions.GROUP_UPDATED, "group", groupId,
                Map.of("name", request.name().trim()), null);
        return loadView(groupId, user.getId(), isSystemAdmin(jwt));
    }

    @Transactional
    public void delete(Jwt jwt, UUID groupId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(groupId);
        requireCanManage(jwt, user.getId(), groupId);
        // Retirer tuples member OpenFGA avant suppression SQL
        List<UUID> members = jdbc.query(
                "SELECT user_id FROM group_members WHERE group_id = ?",
                (rs, i) -> (UUID) rs.getObject("user_id"),
                groupId);
        for (UUID memberId : members) {
            authorizationService.revokePermission("group", groupId, "member", "user", memberId);
        }
        jdbc.update("DELETE FROM groups WHERE id = ?", groupId);
        auditService.record(
                user.getId(), false, AuditActions.GROUP_DELETED, "group", groupId,
                Map.of(), null);
    }

    @Transactional
    public GroupView addMember(Jwt jwt, UUID groupId, AddMemberRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(groupId);
        requireCanManage(jwt, user.getId(), groupId);
        requireUserExists(request.userId());
        jdbc.update(
                "INSERT INTO group_members (group_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                groupId, request.userId());
        authorizationService.grantPermission("group", groupId, "member", "user", request.userId());
        auditService.record(
                user.getId(), false, AuditActions.GROUP_MEMBER_ADDED, "group", groupId,
                Map.of("memberUserId", request.userId().toString()), null);
        return loadView(groupId, user.getId(), isSystemAdmin(jwt));
    }

    @Transactional
    public GroupView removeMember(Jwt jwt, UUID groupId, UUID memberUserId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(groupId);
        requireCanManage(jwt, user.getId(), groupId);
        int deleted = jdbc.update(
                "DELETE FROM group_members WHERE group_id = ? AND user_id = ?",
                groupId, memberUserId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Membre introuvable dans le groupe");
        }
        authorizationService.revokePermission("group", groupId, "member", "user", memberUserId);
        auditService.record(
                user.getId(), false, AuditActions.GROUP_MEMBER_REMOVED, "group", groupId,
                Map.of("memberUserId", memberUserId.toString()), null);
        return loadView(groupId, user.getId(), isSystemAdmin(jwt));
    }

    /** Utilisé par AccessController — rejet si groupId inexistant. */
    public void requireExists(UUID groupId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM groups WHERE id = ?", Integer.class, groupId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupe introuvable");
        }
    }

    private void requireCanManage(Jwt jwt, UUID userId, UUID groupId) {
        if (isSystemAdmin(jwt)) {
            return;
        }
        UUID createdBy = jdbc.query(
                "SELECT created_by FROM groups WHERE id = ?",
                rs -> rs.next() ? (UUID) rs.getObject("created_by") : null,
                groupId);
        if (createdBy == null || !createdBy.equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Seul le créateur du groupe (ou un administrateur système) peut gérer les membres");
        }
    }

    private GroupView loadView(UUID groupId, UUID actorId, boolean admin) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, name, created_by, created_at FROM groups WHERE id = ?", groupId);
        UUID createdBy = (UUID) row.get("created_by");
        Timestamp createdAt = (Timestamp) row.get("created_at");
        List<MemberView> members = jdbc.query("""
                SELECT u.id, u.email, u.display_name
                  FROM group_members gm
                  JOIN users u ON u.id = gm.user_id
                 WHERE gm.group_id = ?
                 ORDER BY u.display_name ASC
                """,
                (rs, i) -> new MemberView(
                        (UUID) rs.getObject("id"),
                        rs.getString("email"),
                        rs.getString("display_name")),
                groupId);
        boolean canManage = admin || (createdBy != null && createdBy.equals(actorId));
        return new GroupView(
                groupId,
                (String) row.get("name"),
                createdBy,
                createdAt != null ? createdAt.toInstant().toString() : null,
                members.size(),
                canManage,
                members);
    }

    private void requireUserExists(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId introuvable");
        }
    }

    private boolean isSystemAdmin(Jwt jwt) {
        return identityFacade.isSystemAdmin(jwt);
    }
}
