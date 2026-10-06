// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.tag;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.tag.TagAdminDtos.CreateTagRequest;
import eu.socle.tag.TagAdminDtos.GovernedAssignmentView;
import eu.socle.tag.TagAdminDtos.MergeTagRequest;
import eu.socle.tag.TagAdminDtos.RenameTagRequest;
import eu.socle.tag.TagAdminDtos.TagAdminSummary;
import eu.socle.tag.TagAdminDtos.TagAdminView;
import eu.socle.tag.TagAdminDtos.TagCreationPolicyRequest;
import eu.socle.tag.TagAdminDtos.TagListResponse;
import eu.socle.user.UserEntity;
import eu.socle.web.ApiErrors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administration des tags (TagsAdmin) — réservé {@code ADMINISTRATEUR_SYSTEME}
 * via {@code /api/v1/admin/**}.
 */
@Service
public class TagAdminService {

    public static final String POLICY_ANY_EDITOR = "any_editor";
    public static final String POLICY_ADMINS_ONLY = "admins_only";

    private static final String GOVERNED_EXPR = """
            EXISTS (SELECT 1 FROM approval_role_assignments ara
                     WHERE ara.scope_type = 'tag' AND lower(ara.scope_ref) = t.id::text)
            """;

    private static final RowMapper<TagAdminView> TAG_MAPPER = (rs, i) -> new TagAdminView(
            (UUID) rs.getObject("id"),
            rs.getString("name"),
            rs.getString("color"),
            rs.getLong("document_count"),
            rs.getString("created_by_display"),
            rs.getTimestamp("created_at") != null
                    ? rs.getTimestamp("created_at").toInstant()
                    : null,
            rs.getBoolean("governed"));

    private final JdbcTemplate jdbc;
    private final IdentityFacade identityFacade;
    private final AuditService auditService;

    public TagAdminService(JdbcTemplate jdbc, IdentityFacade identityFacade, AuditService auditService) {
        this.jdbc = jdbc;
        this.identityFacade = identityFacade;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public TagListResponse list(Jwt jwt) {
        requireAdmin(jwt);
        List<TagAdminView> tags = jdbc.query("""
                SELECT t.id, t.name, t.color, t.created_at,
                       u.display_name AS created_by_display,
                       (SELECT count(*) FROM document_tags dt WHERE dt.tag_id = t.id) AS document_count,
                       """ + GOVERNED_EXPR + """
                       AS governed
                  FROM tags t
                  LEFT JOIN users u ON u.id = t.created_by
                 ORDER BY lower(t.name), t.id
                """, TAG_MAPPER);
        return new TagListResponse(tags, summaryInternal());
    }

    @Transactional(readOnly = true)
    public TagAdminSummary summary(Jwt jwt) {
        requireAdmin(jwt);
        return summaryInternal();
    }

    @Transactional
    public TagAdminView create(Jwt jwt, CreateTagRequest request) {
        UserEntity admin = requireAdmin(jwt);
        String name = TagService.normalizeName(request.name());
        if (existsName(name, null)) {
            throw ApiErrors.tagNameConflict(name);
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO tags (id, name, color, created_by, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                id, name, blankToNull(request.color()), admin.getId(), Timestamp.from(now));
        auditService.record(admin.getId(), false, AuditActions.TAG_CREATED, "tag", id,
                Map.of("name", name), null);
        return requireView(id);
    }

    @Transactional
    public TagAdminView rename(Jwt jwt, UUID tagId, RenameTagRequest request) {
        UserEntity admin = requireAdmin(jwt);
        TagAdminView current = requireView(tagId);
        rejectIfApprovalInProgress(tagId);
        String name = TagService.normalizeName(request.name());
        if (existsName(name, tagId)) {
            throw ApiErrors.tagNameConflict(name);
        }
        jdbc.update("UPDATE tags SET name = ? WHERE id = ?", name, tagId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("from", current.name());
        meta.put("to", name);
        auditService.record(admin.getId(), false, AuditActions.TAG_RENAMED, "tag", tagId, meta, null);
        return requireView(tagId);
    }

    @Transactional
    public void delete(Jwt jwt, UUID tagId) {
        UserEntity admin = requireAdmin(jwt);
        TagAdminView current = requireView(tagId);
        rejectIfApprovalInProgress(tagId);
        List<GovernedAssignmentView> assignments = listGovernedAssignments(tagId);
        if (!assignments.isEmpty()) {
            throw ApiErrors.governedTagInUse(assignments);
        }
        jdbc.update("DELETE FROM document_tags WHERE tag_id = ?", tagId);
        jdbc.update("DELETE FROM tags WHERE id = ?", tagId);
        auditService.record(admin.getId(), false, AuditActions.TAG_DELETED, "tag", tagId,
                Map.of("name", current.name(), "documentCount", current.documentCount()), null);
    }

    @Transactional
    public TagAdminView merge(Jwt jwt, UUID sourceTagId, MergeTagRequest request) {
        UserEntity admin = requireAdmin(jwt);
        if (sourceTagId.equals(request.targetTagId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source et cible identiques");
        }
        TagAdminView source = requireView(sourceTagId);
        TagAdminView target = requireView(request.targetTagId());
        rejectIfApprovalInProgress(sourceTagId);
        rejectIfApprovalInProgress(request.targetTagId());

        List<GovernedAssignmentView> assignments = listGovernedAssignments(sourceTagId);
        if (!assignments.isEmpty() && !request.transferAssignments()) {
            throw ApiErrors.governedTagInUse(assignments);
        }
        if (!assignments.isEmpty()) {
            for (GovernedAssignmentView a : assignments) {
                // Fusion des attributions : si B a déjà la même (role, subject, tag), supprimer A.
                int updated = jdbc.update("""
                        UPDATE approval_role_assignments
                           SET scope_ref = ?
                         WHERE id = ?
                           AND NOT EXISTS (
                             SELECT 1 FROM approval_role_assignments other
                              WHERE other.role_id = approval_role_assignments.role_id
                                AND other.subject_type = approval_role_assignments.subject_type
                                AND other.subject_id = approval_role_assignments.subject_id
                                AND other.scope_type = 'tag'
                                AND lower(other.scope_ref) = lower(?)
                                AND other.id <> approval_role_assignments.id
                           )
                        """,
                        request.targetTagId().toString(), a.assignmentId(),
                        request.targetTagId().toString());
                if (updated == 0) {
                    jdbc.update("DELETE FROM approval_role_assignments WHERE id = ?", a.assignmentId());
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("assignmentId", a.assignmentId().toString());
                meta.put("fromTagId", sourceTagId.toString());
                meta.put("toTagId", request.targetTagId().toString());
                meta.put("roleId", a.roleId().toString());
                meta.put("subjectType", a.subjectType());
                meta.put("subjectId", a.subjectId().toString());
                auditService.record(
                        admin.getId(), false, AuditActions.APPROVAL_ROLE_SCOPE_TRANSFERRED,
                        "approval_role_assignment", a.assignmentId(), meta, null);
            }
        }

        // Réattribue sans doublon : docs déjà sur B → juste retirer A.
        jdbc.update("""
                INSERT INTO document_tags (document_id, tag_id)
                SELECT dt.document_id, ?
                  FROM document_tags dt
                 WHERE dt.tag_id = ?
                   AND NOT EXISTS (
                     SELECT 1 FROM document_tags x
                      WHERE x.document_id = dt.document_id AND x.tag_id = ?
                   )
                """,
                request.targetTagId(), sourceTagId, request.targetTagId());
        jdbc.update("DELETE FROM document_tags WHERE tag_id = ?", sourceTagId);
        jdbc.update("DELETE FROM tags WHERE id = ?", sourceTagId);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sourceTagId", sourceTagId.toString());
        meta.put("sourceName", source.name());
        meta.put("targetTagId", request.targetTagId().toString());
        meta.put("targetName", target.name());
        meta.put("transferredAssignments", !assignments.isEmpty());
        auditService.record(admin.getId(), false, AuditActions.TAG_MERGED, "tag", request.targetTagId(),
                meta, null);
        return requireView(request.targetTagId());
    }

    @Transactional
    public TagAdminSummary updateCreationPolicy(Jwt jwt, TagCreationPolicyRequest request) {
        UserEntity admin = requireAdmin(jwt);
        String policy = normalizePolicy(request.tagCreationPolicy());
        jdbc.update("""
                INSERT INTO instance_settings (id, tag_creation_policy, updated_at)
                VALUES (true, ?, now())
                ON CONFLICT (id) DO UPDATE
                  SET tag_creation_policy = EXCLUDED.tag_creation_policy,
                      updated_at = now()
                """, policy);
        auditService.record(admin.getId(), false, AuditActions.TAG_CREATION_POLICY_CHANGED,
                "instance_settings", null, Map.of("tagCreationPolicy", policy), null);
        return summaryInternal();
    }

    /** Lecture interne (attach document) — pas d'exigence admin. */
    @Transactional(readOnly = true)
    public String currentCreationPolicy() {
        List<String> rows = jdbc.query(
                "SELECT tag_creation_policy FROM instance_settings WHERE id = true",
                (rs, i) -> rs.getString(1));
        return rows.isEmpty() ? POLICY_ANY_EDITOR : rows.getFirst();
    }

    private TagAdminSummary summaryInternal() {
        Long tagCount = jdbc.queryForObject("SELECT count(*) FROM tags", Long.class);
        Long tagged = jdbc.queryForObject(
                "SELECT count(DISTINCT document_id) FROM document_tags", Long.class);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE deleted_at IS NULL", Long.class);
        return new TagAdminSummary(
                tagCount == null ? 0 : tagCount,
                tagged == null ? 0 : tagged,
                total == null ? 0 : total,
                currentCreationPolicy());
    }

    private UserEntity requireAdmin(Jwt jwt) {
        if (!identityFacade.isSystemAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrateur système requis");
        }
        return identityFacade.sync(jwt);
    }

    private TagAdminView requireView(UUID tagId) {
        List<TagAdminView> rows = jdbc.query("""
                SELECT t.id, t.name, t.color, t.created_at,
                       u.display_name AS created_by_display,
                       (SELECT count(*) FROM document_tags dt WHERE dt.tag_id = t.id) AS document_count,
                       """ + GOVERNED_EXPR + """
                       AS governed
                  FROM tags t
                  LEFT JOIN users u ON u.id = t.created_by
                 WHERE t.id = ?
                """, TAG_MAPPER, tagId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag introuvable");
        }
        return rows.getFirst();
    }

    private boolean existsName(String name, UUID exceptId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM tags
                 WHERE lower(name) = lower(?)
                   AND (?::uuid IS NULL OR id <> ?::uuid)
                """, Integer.class, name, exceptId, exceptId);
        return n != null && n > 0;
    }

    private void rejectIfApprovalInProgress(UUID tagId) {
        Integer pending = jdbc.queryForObject("""
                SELECT count(*)
                  FROM document_tags dt
                  JOIN approval_requests ar ON ar.document_id = dt.document_id
                 WHERE dt.tag_id = ? AND ar.status = 'en_cours'
                """, Integer.class, tagId);
        if (pending != null && pending > 0) {
            throw ApiErrors.approvalInProgress();
        }
    }

    private List<GovernedAssignmentView> listGovernedAssignments(UUID tagId) {
        return jdbc.query("""
                SELECT ara.id, ara.role_id, gr.name AS role_name,
                       ara.subject_type, ara.subject_id,
                       CASE
                         WHEN ara.subject_type = 'user' THEN u.display_name
                         ELSE g.name
                       END AS subject_display
                  FROM approval_role_assignments ara
                  JOIN global_roles gr ON gr.id = ara.role_id
                  LEFT JOIN users u ON ara.subject_type = 'user' AND u.id = ara.subject_id
                  LEFT JOIN groups g ON ara.subject_type = 'group' AND g.id = ara.subject_id
                 WHERE ara.scope_type = 'tag' AND lower(ara.scope_ref) = lower(?)
                 ORDER BY gr.name, ara.subject_type, ara.id
                """,
                (rs, i) -> new GovernedAssignmentView(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("role_id"),
                        rs.getString("role_name"),
                        rs.getString("subject_type"),
                        (UUID) rs.getObject("subject_id"),
                        rs.getString("subject_display")),
                tagId.toString());
    }

    private static String normalizePolicy(String raw) {
        if (POLICY_ANY_EDITOR.equals(raw) || POLICY_ADMINS_ONLY.equals(raw)) {
            return raw;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Politique invalide (any_editor|admins_only)");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
