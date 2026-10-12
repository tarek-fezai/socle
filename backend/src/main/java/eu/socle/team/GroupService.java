// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
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
import eu.socle.team.GroupDtos.UpdateMemberRoleRequest;
import eu.socle.user.Initials;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD équipes / membres / rôles — {@code docs/teams-and-access.md} § 1.
 */
@Service
public class GroupService {

    public static final String LAST_TEAM_OWNER = "last_team_owner";

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
        insertMember(id, user.getId(), TeamRole.proprietaire);
        auditService.record(
                user.getId(), false, AuditActions.GROUP_CREATED, "group", id,
                Map.of("name", request.name().trim()), null);
        return loadView(id, user.getId(), isSystemAdmin(jwt));
    }

    @Transactional
    public GroupView update(Jwt jwt, UUID groupId, UpdateGroupRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(groupId);
        requireCanManageTeam(jwt, user.getId(), groupId);
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
        requireCanManageTeam(jwt, user.getId(), groupId);
        List<MemberRow> members = loadMemberRows(groupId);
        for (MemberRow m : members) {
            authorizationService.revokePermission("group", groupId, m.role().name(), "user", m.userId());
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
        requireCanManageTeam(jwt, user.getId(), groupId);
        requireUserExists(request.userId());
        TeamRole role = request.role() == null || request.role().isBlank()
                ? TeamRole.contributeur
                : TeamRole.parse(request.role());
        insertMember(groupId, request.userId(), role);
        auditService.record(
                user.getId(), false, AuditActions.GROUP_MEMBER_ADDED, "group", groupId,
                Map.of("memberUserId", request.userId().toString(), "role", role.name()), null);
        return loadView(groupId, user.getId(), isSystemAdmin(jwt));
    }

    @Transactional
    public GroupView updateMemberRole(Jwt jwt, UUID groupId, UUID memberUserId, UpdateMemberRoleRequest request) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(groupId);
        requireCanManageTeam(jwt, user.getId(), groupId);
        TeamRole next = TeamRole.parse(request.role());
        TeamRole current = currentRole(groupId, memberUserId);
        if (current == next) {
            return loadView(groupId, user.getId(), isSystemAdmin(jwt));
        }
        if (current == TeamRole.proprietaire && next != TeamRole.proprietaire) {
            requireNotLastOwner(groupId, memberUserId);
        }
        jdbc.update(
                "UPDATE group_members SET role = ? WHERE group_id = ? AND user_id = ?",
                next.name(), groupId, memberUserId);
        authorizationService.revokePermission("group", groupId, current.name(), "user", memberUserId);
        authorizationService.grantPermission("group", groupId, next.name(), "user", memberUserId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("memberUserId", memberUserId.toString());
        meta.put("before", current.name());
        meta.put("after", next.name());
        auditService.record(
                user.getId(), false, AuditActions.GROUP_MEMBER_ROLE_CHANGED, "group", groupId, meta, null);
        return loadView(groupId, user.getId(), isSystemAdmin(jwt));
    }

    @Transactional
    public GroupView removeMember(Jwt jwt, UUID groupId, UUID memberUserId) {
        var user = userSyncService.syncFromJwt(jwt);
        requireExists(groupId);
        requireCanManageTeam(jwt, user.getId(), groupId);
        TeamRole current = currentRole(groupId, memberUserId);
        if (current == TeamRole.proprietaire) {
            requireNotLastOwner(groupId, memberUserId);
        }
        int deleted = jdbc.update(
                "DELETE FROM group_members WHERE group_id = ? AND user_id = ?",
                groupId, memberUserId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Membre introuvable dans le groupe");
        }
        authorizationService.revokePermission("group", groupId, current.name(), "user", memberUserId);
        auditService.record(
                user.getId(), false, AuditActions.GROUP_MEMBER_REMOVED, "group", groupId,
                Map.of("memberUserId", memberUserId.toString(), "role", current.name()), null);
        return loadView(groupId, user.getId(), isSystemAdmin(jwt));
    }

    public void requireExists(UUID groupId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM groups WHERE id = ?", Integer.class, groupId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupe introuvable");
        }
    }

    private void insertMember(UUID groupId, UUID userId, TeamRole role) {
        jdbc.update(
                """
                INSERT INTO group_members (group_id, user_id, role) VALUES (?, ?, ?)
                ON CONFLICT (group_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """,
                groupId, userId, role.name());
        authorizationService.grantPermission("group", groupId, role.name(), "user", userId);
    }

    private void requireCanManageTeam(Jwt jwt, UUID userId, UUID groupId) {
        if (isSystemAdmin(jwt)) {
            return;
        }
        String role = jdbc.query(
                "SELECT role FROM group_members WHERE group_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString("role") : null,
                groupId, userId);
        if (!TeamRole.proprietaire.name().equals(role)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Seul un propriétaire de l'équipe (ou un administrateur système) peut gérer les membres");
        }
    }

    private void requireNotLastOwner(UUID groupId, UUID memberUserId) {
        Integer owners = jdbc.queryForObject(
                "SELECT count(*) FROM group_members WHERE group_id = ? AND role = 'proprietaire'",
                Integer.class,
                groupId);
        if (owners != null && owners <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    LAST_TEAM_OWNER + ": dernier propriétaire d'équipe non retirable");
        }
    }

    private TeamRole currentRole(UUID groupId, UUID memberUserId) {
        String role = jdbc.query(
                "SELECT role FROM group_members WHERE group_id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString("role") : null,
                groupId, memberUserId);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Membre introuvable dans le groupe");
        }
        return TeamRole.parse(role);
    }

    private GroupView loadView(UUID groupId, UUID actorId, boolean admin) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, name, created_by, created_at FROM groups WHERE id = ?", groupId);
        UUID createdBy = (UUID) row.get("created_by");
        Timestamp createdAt = (Timestamp) row.get("created_at");
        List<MemberView> members = jdbc.query("""
                SELECT u.id, u.email, u.display_name, u.avatar_initials, gm.role
                  FROM group_members gm
                  JOIN users u ON u.id = gm.user_id
                 WHERE gm.group_id = ?
                 ORDER BY u.display_name ASC
                """,
                (rs, i) -> new MemberView(
                        (UUID) rs.getObject("id"),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        Initials.of(rs.getString("display_name"), rs.getString("avatar_initials"))),
                groupId);
        boolean canManage = admin || members.stream()
                .anyMatch(m -> m.userId().equals(actorId) && TeamRole.proprietaire.name().equals(m.role()));
        return new GroupView(
                groupId,
                (String) row.get("name"),
                createdBy,
                createdAt != null ? createdAt.toInstant().toString() : null,
                members.size(),
                canManage,
                members);
    }

    private List<MemberRow> loadMemberRows(UUID groupId) {
        return jdbc.query(
                "SELECT user_id, role FROM group_members WHERE group_id = ?",
                (rs, i) -> new MemberRow((UUID) rs.getObject("user_id"), TeamRole.parse(rs.getString("role"))),
                groupId);
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

    private record MemberRow(UUID userId, TeamRole role) {}
}
