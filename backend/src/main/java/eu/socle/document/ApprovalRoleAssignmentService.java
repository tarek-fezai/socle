package eu.socle.document;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.SocleRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ApprovalRoleAssignmentService {

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public ApprovalRoleAssignmentService(JdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    public List<AssignmentView> list(UUID actorId, UUID roleId, UUID spaceId) {
        if (isAdministrateurSysteme()) {
            if (roleId != null) {
                return jdbc.query(BASE_SELECT + " WHERE ara.role_id = ? ORDER BY ara.granted_at DESC",
                        (rs, i) -> map(rs), roleId);
            }
            if (spaceId != null) {
                return jdbc.query(BASE_SELECT
                                + " WHERE ara.scope_type = 'space' AND ara.scope_ref = ? ORDER BY ara.granted_at DESC",
                        (rs, i) -> map(rs), spaceId.toString());
            }
            return jdbc.query(BASE_SELECT + " ORDER BY ara.granted_at DESC", (rs, i) -> map(rs));
        }

        List<UUID> owned = ownedSpaceIds(actorId);
        if (owned.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", owned.stream().map(u -> "?").toList());
        Object[] args = owned.stream().map(UUID::toString).toArray();
        String sql = BASE_SELECT
                + " WHERE ara.scope_type = 'space' AND ara.scope_ref IN (" + placeholders + ")";
        if (roleId != null) {
            sql += " AND ara.role_id = ?";
            Object[] withRole = new Object[args.length + 1];
            System.arraycopy(args, 0, withRole, 0, args.length);
            withRole[args.length] = roleId;
            return jdbc.query(sql + " ORDER BY ara.granted_at DESC", (rs, i) -> map(rs), withRole);
        }
        if (spaceId != null) {
            if (!owned.contains(spaceId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pas propriétaire de cet espace");
            }
            return jdbc.query(BASE_SELECT
                            + " WHERE ara.scope_type = 'space' AND ara.scope_ref = ? ORDER BY ara.granted_at DESC",
                    (rs, i) -> map(rs), spaceId.toString());
        }
        return jdbc.query(sql + " ORDER BY ara.granted_at DESC", (rs, i) -> map(rs), args);
    }

    public AssignmentView assign(UUID actorId, AssignRequest req) {
        String subjectType = normalizeSubjectType(req.subjectType());
        String scopeType = normalizeScopeType(req.scopeType());
        String scopeRef = normalizeScopeRef(scopeType, req.scopeRef());
        UUID subjectId = req.subjectId();
        UUID roleId = req.roleId();

        requireRoleExists(roleId);
        requireSubjectExists(subjectType, subjectId);
        authorizeMutation(actorId, scopeType, scopeRef);

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO approval_role_assignments
                      (id, role_id, subject_type, subject_id, scope_type, scope_ref, granted_by, granted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, now())
                    """,
                    id, roleId, subjectType, subjectId, scopeType, scopeRef, actorId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attribution déjà existante");
        }

        auditService.recordSync(
                actorId,
                true,
                AuditActions.APPROVAL_ROLE_ASSIGNED,
                "approval_role_assignment",
                id,
                Map.of(
                        "roleId", roleId.toString(),
                        "subjectType", subjectType,
                        "subjectId", subjectId.toString(),
                        "scopeType", scopeType,
                        "scopeRef", scopeRef != null ? scopeRef : ""
                ),
                null
        );
        return get(id);
    }

    public void unassign(UUID actorId, UUID assignmentId) {
        AssignmentView existing = get(assignmentId);
        authorizeMutation(actorId, existing.scopeType(), existing.scopeRef());
        int n = jdbc.update("DELETE FROM approval_role_assignments WHERE id = ?", assignmentId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attribution introuvable");
        }
        auditService.recordSync(
                actorId,
                true,
                AuditActions.APPROVAL_ROLE_UNASSIGNED,
                "approval_role_assignment",
                assignmentId,
                Map.of(
                        "roleId", existing.roleId().toString(),
                        "subjectType", existing.subjectType(),
                        "subjectId", existing.subjectId().toString(),
                        "scopeType", existing.scopeType(),
                        "scopeRef", existing.scopeRef() != null ? existing.scopeRef() : ""
                ),
                null
        );
    }

    private AssignmentView get(UUID id) {
        List<AssignmentView> rows = jdbc.query(
                BASE_SELECT + " WHERE ara.id = ?",
                (rs, i) -> map(rs),
                id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attribution introuvable");
        }
        return rows.getFirst();
    }

    private void authorizeMutation(UUID actorId, String scopeType, String scopeRef) {
        if (isAdministrateurSysteme()) {
            return;
        }
        if (!"space".equals(scopeType) || scopeRef == null || scopeRef.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Seuls les administrateurs système peuvent attribuer hors portée espace");
        }
        UUID spaceId;
        try {
            spaceId = UUID.fromString(scopeRef);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeRef espace invalide");
        }
        if (!ownedSpaceIds(actorId).contains(spaceId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Pas propriétaire de cet espace");
        }
    }

    private List<UUID> ownedSpaceIds(UUID userId) {
        return jdbc.query(
                "SELECT space_id FROM space_owners WHERE user_id = ?",
                (rs, i) -> (UUID) rs.getObject("space_id"),
                userId);
    }

    private void requireRoleExists(UUID roleId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM global_roles WHERE id = ?", Integer.class, roleId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rôle inconnu");
        }
    }

    private void requireSubjectExists(String subjectType, UUID subjectId) {
        String table = "user".equals(subjectType) ? "users" : "groups";
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, subjectId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sujet introuvable");
        }
    }

    private static String normalizeSubjectType(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectType requis");
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!"user".equals(v) && !"group".equals(v)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectType user|group");
        }
        return v;
    }

    private static String normalizeScopeType(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeType requis");
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!List.of("all", "space", "tag", "doc_type").contains(v)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeType all|space|tag|doc_type");
        }
        return v;
    }

    private static String normalizeScopeRef(String scopeType, String scopeRef) {
        if ("all".equals(scopeType)) {
            if (scopeRef != null && !scopeRef.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeRef doit être vide pour all");
            }
            return null;
        }
        if (scopeRef == null || scopeRef.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeRef requis pour " + scopeType);
        }
        String trimmed = scopeRef.trim();
        if ("space".equals(scopeType) || "tag".equals(scopeType)) {
            try {
                return UUID.fromString(trimmed).toString();
            } catch (IllegalArgumentException e) {
                if ("tag".equals(scopeType)) {
                    return trimmed; // nom de tag accepté
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeRef UUID invalide");
            }
        }
        return trimmed;
    }

    private static boolean isAdministrateurSysteme() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> SocleRole.ADMINISTRATEUR_SYSTEME.authority().equals(a.getAuthority()));
    }

    private static final String BASE_SELECT = """
            SELECT ara.id, ara.role_id, gr.name AS role_name,
                   ara.subject_type, ara.subject_id, ara.scope_type, ara.scope_ref,
                   ara.granted_by, ara.granted_at
              FROM approval_role_assignments ara
              JOIN global_roles gr ON gr.id = ara.role_id
            """;

    private static AssignmentView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp granted = rs.getTimestamp("granted_at");
        return new AssignmentView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("role_id"),
                rs.getString("role_name"),
                rs.getString("subject_type"),
                (UUID) rs.getObject("subject_id"),
                rs.getString("scope_type"),
                rs.getString("scope_ref"),
                (UUID) rs.getObject("granted_by"),
                granted != null ? granted.toInstant().toString() : null
        );
    }

    public record AssignmentView(
            UUID id,
            UUID roleId,
            String roleName,
            String subjectType,
            UUID subjectId,
            String scopeType,
            String scopeRef,
            UUID grantedBy,
            String grantedAt
    ) {}

    public record AssignRequest(
            UUID roleId,
            String subjectType,
            UUID subjectId,
            String scopeType,
            String scopeRef
    ) {}
}
