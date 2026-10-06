// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Résolution des attributions de rôles d'approbation scopées
 * ({@code approval_role_assignments}).
 *
 * <p>{@link #canDecide} est vrai si une attribution du rôle (user direct ou via
 * groupe membre) couvre le document : {@code all}, {@code space}, {@code tag}
 * (tags au moment de l'appel), {@code doc_type}.
 */
@Service
public class ApprovalRoleResolver {

    private final JdbcTemplate jdbc;

    public ApprovalRoleResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * L'utilisateur peut-il décider au titre de {@code roleId} sur {@code documentId} ?
     * Si {@code roleId} est null, fallback sur le rôle nommé « Éditeur de documents ».
     */
    public boolean canDecide(UUID userId, UUID roleId, UUID documentId) {
        if (userId == null || documentId == null) {
            return false;
        }
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1
                    FROM documents d
                   WHERE d.id = ?
                     AND d.deleted_at IS NULL
                     AND EXISTS (
                       SELECT 1
                         FROM approval_role_assignments ara
                        WHERE ara.role_id = COALESCE(
                                ?,
                                (SELECT gr.id FROM global_roles gr
                                  WHERE gr.name = 'Éditeur de documents' LIMIT 1)
                              )
                          AND (
                                (ara.subject_type = 'user' AND ara.subject_id = ?)
                             OR (ara.subject_type = 'group' AND EXISTS (
                                   SELECT 1 FROM group_members gm
                                    WHERE gm.group_id = ara.subject_id
                                      AND gm.user_id = ?
                                 ))
                              )
                          AND (
                                ara.scope_type = 'all'
                             OR (ara.scope_type = 'space'
                                 AND ara.scope_ref = d.space_id::text)
                             OR (ara.scope_type = 'doc_type'
                                 AND ara.scope_ref IS NOT DISTINCT FROM d.doc_type)
                             OR (ara.scope_type = 'tag' AND EXISTS (
                                   SELECT 1
                                     FROM document_tags dt
                                    WHERE dt.document_id = d.id
                                      AND ara.scope_ref = dt.tag_id::text
                                 ))
                              )
                     )
                )
                """,
                Boolean.class,
                documentId, roleId, userId, userId);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Approbateur de l'étape courante de la demande (rôle d'étape + document lié).
     */
    public boolean canDecideCurrentStep(UUID userId, UUID approvalRequestId) {
        List<RoleDoc> rows = jdbc.query("""
                SELECT aws.approver_role_id AS role_id, ar.document_id
                  FROM approval_requests ar
                  JOIN approval_workflow_steps aws
                    ON aws.workflow_id = ar.workflow_id
                   AND aws.step_order = ar.current_step_order
                 WHERE ar.id = ?
                """,
                (rs, i) -> new RoleDoc(
                        (UUID) rs.getObject("role_id"),
                        (UUID) rs.getObject("document_id")
                ),
                approvalRequestId);
        if (rows.isEmpty()) {
            return false;
        }
        RoleDoc rd = rows.getFirst();
        return canDecide(userId, rd.roleId(), rd.documentId());
    }

    /**
     * Utilisateurs (et membres de groupes) couverts par le rôle pour ce document.
     * N'inclut pas le demandeur — à fusionner côté appelant si besoin.
     */
    public List<UUID> resolveInScopeAssignees(UUID roleId, UUID documentId) {
        if (roleId == null || documentId == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT DISTINCT uid AS user_id FROM (
                  SELECT ara.subject_id AS uid
                    FROM approval_role_assignments ara
                    JOIN documents d ON d.id = ?
                   WHERE ara.role_id = ?
                     AND ara.subject_type = 'user'
                     AND d.deleted_at IS NULL
                     AND (
                           ara.scope_type = 'all'
                        OR (ara.scope_type = 'space' AND ara.scope_ref = d.space_id::text)
                        OR (ara.scope_type = 'doc_type'
                            AND ara.scope_ref IS NOT DISTINCT FROM d.doc_type)
                        OR (ara.scope_type = 'tag' AND EXISTS (
                              SELECT 1 FROM document_tags dt
                             WHERE dt.document_id = d.id
                               AND ara.scope_ref = dt.tag_id::text
                            ))
                     )
                  UNION
                  SELECT gm.user_id AS uid
                    FROM approval_role_assignments ara
                    JOIN documents d ON d.id = ?
                    JOIN group_members gm ON gm.group_id = ara.subject_id
                   WHERE ara.role_id = ?
                     AND ara.subject_type = 'group'
                     AND d.deleted_at IS NULL
                     AND (
                           ara.scope_type = 'all'
                        OR (ara.scope_type = 'space' AND ara.scope_ref = d.space_id::text)
                        OR (ara.scope_type = 'doc_type'
                            AND ara.scope_ref IS NOT DISTINCT FROM d.doc_type)
                        OR (ara.scope_type = 'tag' AND EXISTS (
                              SELECT 1 FROM document_tags dt
                             WHERE dt.document_id = d.id
                               AND ara.scope_ref = dt.tag_id::text
                            ))
                     )
                ) covered
                """,
                (rs, i) -> (UUID) rs.getObject("user_id"),
                documentId, roleId, documentId, roleId);
    }

    private record RoleDoc(UUID roleId, UUID documentId) {}
}
