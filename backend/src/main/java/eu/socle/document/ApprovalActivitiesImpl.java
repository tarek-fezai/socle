// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.activity.ActivityEventService;
import eu.socle.activity.ActivityEventTypes;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.storage.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ApprovalActivitiesImpl implements ApprovalActivities {

    private static final Logger log = LoggerFactory.getLogger(ApprovalActivitiesImpl.class);

    public static final UUID SYSTEM_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final ReliabilityScoreService reliabilityScoreService;
    /** Conservé pour compatibilité DI / tests ; la soumission n'écrit plus via le store. */
    @SuppressWarnings("unused")
    private final DocumentStore documentStore;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    private final ApprovalRoleResolver approvalRoleResolver;
    private ActivityEventService activityEventService;

    public ApprovalActivitiesImpl(
            JdbcTemplate jdbcTemplate,
            AuditService auditService,
            ReliabilityScoreService reliabilityScoreService,
            DocumentStore documentStore,
            ObjectMapper objectMapper,
            ApprovalRoleResolver approvalRoleResolver
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.reliabilityScoreService = reliabilityScoreService;
        this.documentStore = documentStore;
        this.objectMapper = objectMapper;
        this.approvalRoleResolver = approvalRoleResolver;
    }

    @Autowired(required = false)
    void setActivityEventService(ActivityEventService activityEventService) {
        this.activityEventService = activityEventService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalStepDef> loadWorkflowSteps(UUID workflowDefId) {
        List<ApprovalStepDef> steps = jdbcTemplate.query("""
                SELECT step_order, sla_hours, escalates_to_step_order
                  FROM approval_workflow_steps
                 WHERE workflow_id = ?
                 ORDER BY step_order ASC
                """,
                (rs, i) -> new ApprovalStepDef(
                        rs.getInt("step_order"),
                        (Integer) rs.getObject("sla_hours"),
                        (Integer) rs.getObject("escalates_to_step_order")
                ),
                workflowDefId);
        if (steps.isEmpty()) {
            return List.of(new ApprovalStepDef(1, 24, null));
        }
        return steps;
    }

    @Override
    @Transactional
    public String recordSubmission(
            UUID documentId,
            UUID requesterId,
            UUID approvalRequestId,
            UUID workflowDefId,
            String temporalWorkflowId,
            int firstStepOrder,
            int firstStepSlaHours
    ) {
        log.info("Temporal recordSubmission document={} request={} workflowId={} step={}",
                documentId, approvalRequestId, temporalWorkflowId, firstStepOrder);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT current_version_no, git_head_sha
                  FROM documents
                 WHERE id = ? AND deleted_at IS NULL
                """, documentId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Document introuvable pour soumission: " + documentId);
        }
        Map<String, Object> docRow = rows.getFirst();
        int submittedVersionNo = ((Number) docRow.get("current_version_no")).intValue();
        String contentHeadSha = docRow.get("git_head_sha") == null
                ? null
                : String.valueOf(docRow.get("git_head_sha"));

        // Pas de version dupliquée : le contenu courant est soumis tel quel.
        // submitted_version_no = current_version_no ; current_change_summary inchangé ;
        // aucune écriture DocumentStore (ni commit git).
        jdbcTemplate.update("""
                UPDATE documents
                   SET status = 'en_revue',
                       updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                """,
                documentId);

        // Empreinte = contenu sous revue (égal à current_* au moment de la soumission).
        jdbcTemplate.update("""
                INSERT INTO approval_requests
                  (id, document_id, workflow_id, temporal_workflow_id, requested_by,
                   current_step_order, status, sla_deadline_at, created_at, submitted_version_no,
                   submitted_content_version_no, submitted_git_head_sha)
                VALUES (?, ?, ?, ?, ?, ?, 'en_cours', now() + make_interval(hours => ?), now(), ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                approvalRequestId, documentId, workflowDefId, temporalWorkflowId, requesterId,
                firstStepOrder, firstStepSlaHours, submittedVersionNo,
                submittedVersionNo, contentHeadSha);

        auditService.recordSync(
                requesterId,
                false,
                AuditActions.DOCUMENT_SUBMITTED,
                "document",
                documentId,
                Map.of(
                        "approvalRequestId", approvalRequestId.toString(),
                        "temporalWorkflowId", temporalWorkflowId,
                        "stepOrder", firstStepOrder,
                        "submittedVersionNo", submittedVersionNo
                ),
                null
        );

        if (activityEventService != null) {
            activityEventService.record(
                    ActivityEventTypes.SUBMISSION,
                    requesterId,
                    documentId,
                    loadSpaceId(documentId),
                    Map.of("approvalRequestId", approvalRequestId.toString()));
        }

        return "en_cours";
    }

    @Override
    @Transactional
    public String recordStepApproved(
            UUID approvalRequestId,
            int stepOrder,
            UUID actorId,
            String comment
    ) {
        jdbcTemplate.update("""
                INSERT INTO approval_actions
                  (id, approval_request_id, step_order, actor_id, decision, comment, acted_at)
                VALUES (?, ?, ?, ?, 'approuve', ?, now())
                """,
                UUID.randomUUID(), approvalRequestId, stepOrder, actorId, comment);
        return "step_approuve";
    }

    @Override
    @Transactional
    public String advanceToStep(
            UUID approvalRequestId,
            int newStepOrder,
            int newStepSlaHours
    ) {
        // Même verrou ligne que decide — sérialise escalade / décision concurrentes.
        jdbcTemplate.queryForList(
                "SELECT id FROM approval_requests WHERE id = ? FOR UPDATE",
                approvalRequestId);
        int updated = jdbcTemplate.update("""
                UPDATE approval_requests
                   SET current_step_order = ?,
                       sla_deadline_at = now() + make_interval(hours => ?)
                 WHERE id = ? AND status = 'en_cours'
                """,
                newStepOrder, newStepSlaHours, approvalRequestId);
        return updated > 0 ? "step_" + newStepOrder : "noop_resolved";
    }

    @Override
    @Transactional
    public String recordSlaEscalation(
            UUID approvalRequestId,
            int fromStepOrder,
            int toStepOrder,
            int toStepSlaHours,
            UUID systemActorId
    ) {
        log.info("SLA escalation request={} {} → {}", approvalRequestId, fromStepOrder, toStepOrder);

        List<Map<String, Object>> locked = jdbcTemplate.queryForList("""
                SELECT status, current_step_order
                  FROM approval_requests
                 WHERE id = ?
                 FOR UPDATE
                """, approvalRequestId);
        if (locked.isEmpty()) {
            return "noop_missing";
        }
        Map<String, Object> row = locked.getFirst();
        if (!"en_cours".equals(row.get("status"))) {
            // decide a déjà clôturé — l'escalade perd de façon déterministe
            return "noop_resolved";
        }
        int actualStep = ((Number) row.get("current_step_order")).intValue();
        if (actualStep != fromStepOrder) {
            // decide a déjà avancé ou une autre escalade a gagné
            return "noop_step_mismatch";
        }

        jdbcTemplate.update("""
                INSERT INTO approval_actions
                  (id, approval_request_id, step_order, actor_id, decision, comment, acted_at)
                VALUES (?, ?, ?, ?, 'reassigne', ?, now())
                """,
                UUID.randomUUID(),
                approvalRequestId,
                fromStepOrder,
                systemActorId,
                "Escalade automatique SLA (étape " + fromStepOrder + " → " + toStepOrder + ")");

        jdbcTemplate.update("""
                UPDATE approval_requests
                   SET current_step_order = ?,
                       sla_deadline_at = now() + make_interval(hours => ?)
                 WHERE id = ? AND status = 'en_cours' AND current_step_order = ?
                """,
                toStepOrder, toStepSlaHours, approvalRequestId, fromStepOrder);

        UUID documentId = jdbcTemplate.queryForObject(
                "SELECT document_id FROM approval_requests WHERE id = ?",
                UUID.class,
                approvalRequestId);

        auditService.recordSync(
                null,
                true,
                AuditActions.APPROVAL_ESCALATED,
                "document",
                documentId,
                Map.of(
                        "approvalRequestId", approvalRequestId.toString(),
                        "fromStepOrder", fromStepOrder,
                        "toStepOrder", toStepOrder
                ),
                null
        );

        // Plus aucun approbateur éligible (portée + quatre yeux) → bloqué, pas d'auto-approbation.
        if (!hasEligibleApprovers(approvalRequestId, toStepOrder)) {
            UUID requesterId = jdbcTemplate.queryForObject(
                    "SELECT requested_by FROM approval_requests WHERE id = ?",
                    UUID.class,
                    approvalRequestId);
            return recordChainExhausted(
                    documentId, approvalRequestId, toStepOrder, requesterId, systemActorId);
        }

        return "escalade_" + toStepOrder;
    }

    @Override
    @Transactional
    public String recordChainExhausted(
            UUID documentId,
            UUID approvalRequestId,
            int lastStepOrder,
            UUID requesterId,
            UUID systemActorId
    ) {
        log.warn("SLA chain exhausted request={} lastStep={} — reste en_cours, notification UI (pas d'auto-décision)",
                approvalRequestId, lastStepOrder);

        jdbcTemplate.update("""
                INSERT INTO approval_actions
                  (id, approval_request_id, step_order, actor_id, decision, comment, acted_at)
                VALUES (?, ?, ?, ?, 'reassigne', ?, now())
                """,
                UUID.randomUUID(),
                approvalRequestId,
                lastStepOrder,
                systemActorId,
                "Chaîne SLA épuisée — intervention manuelle requise (pas d'auto-approbation)");

        jdbcTemplate.update("""
                UPDATE approval_requests
                   SET sla_deadline_at = NULL
                 WHERE id = ? AND status = 'en_cours'
                """,
                approvalRequestId);

        List<UUID> recipients = resolveChainExhaustedRecipients(approvalRequestId, lastStepOrder, requesterId);
        String payloadJson = """
                {"document_id":"%s","approval_request_id":"%s","steps_traversed":%d,"message":"Chaîne d'approbation SLA épuisée — intervention manuelle requise"}
                """.formatted(documentId, approvalRequestId, lastStepOrder).trim();

        for (UUID userId : recipients) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO notifications (id, user_id, type, payload, created_at)
                        VALUES (?, ?, 'approval_chain_exhausted', CAST(? AS jsonb), now())
                        """,
                        UUID.randomUUID(), userId, payloadJson);
            } catch (Exception e) {
                log.error("Échec INSERT notification approval_chain_exhausted user={} request={}",
                        userId, approvalRequestId, e);
                throw e;
            }
        }

        Map<String, Object> auditMeta = new java.util.LinkedHashMap<>();
        auditMeta.put("approvalRequestId", approvalRequestId.toString());
        auditMeta.put("lastStepOrder", lastStepOrder);
        auditMeta.put("stepsTraversed", lastStepOrder);
        auditMeta.put("notifiedUserIds", recipients.stream().map(UUID::toString).toList());

        auditService.recordSync(
                null,
                true,
                AuditActions.APPROVAL_CHAIN_EXHAUSTED,
                "document",
                documentId,
                auditMeta,
                null
        );

        return "en_cours_alerte";
    }

    /**
     * Destinataires d'alerte chaîne épuisée : demandeur (toujours) + approbateurs
     * in-scope hors demandeur et contributeurs de contenu (quatre yeux).
     */
    private List<UUID> resolveChainExhaustedRecipients(
            UUID approvalRequestId,
            int lastStepOrder,
            UUID requesterId
    ) {
        UUID documentIdForContributors = loadDocumentId(approvalRequestId);
        java.util.Set<UUID> contributors = documentIdForContributors == null
                ? java.util.Set.of()
                : FourEyesPolicy.loadContentContributors(jdbcTemplate, documentIdForContributors);
        List<UUID> recipients = new java.util.ArrayList<>();
        if (requesterId != null) {
            recipients.add(requesterId);
        }

        List<Map<String, Object>> ctx = jdbcTemplate.queryForList("""
                SELECT s.approver_role_id AS role_id, ar.document_id
                  FROM approval_requests ar
                  JOIN approval_workflow_steps s
                    ON s.workflow_id = ar.workflow_id AND s.step_order = ?
                 WHERE ar.id = ?
                """,
                lastStepOrder,
                approvalRequestId);

        if (!ctx.isEmpty()) {
            Map<String, Object> row = ctx.getFirst();
            UUID roleId = row.get("role_id") instanceof UUID u
                    ? u
                    : (row.get("role_id") != null ? UUID.fromString(String.valueOf(row.get("role_id"))) : null);
            UUID documentId = row.get("document_id") instanceof UUID u
                    ? u
                    : UUID.fromString(String.valueOf(row.get("document_id")));
            if (roleId != null) {
                for (UUID approverId : approvalRoleResolver.resolveInScopeAssignees(roleId, documentId)) {
                    if (FourEyesPolicy.isConflict(approverId, requesterId, contributors)) {
                        continue;
                    }
                    if (!recipients.contains(approverId)) {
                        recipients.add(approverId);
                    }
                }
            } else {
                log.warn("Chaîne épuisée request={} : aucun rôle sur l'étape {}",
                        approvalRequestId, lastStepOrder);
            }
        }

        if (recipients.size() <= 1) {
            log.warn("Chaîne épuisée request={} : aucun approbateur éligible pour l'étape {} (quatre yeux / portée)",
                    approvalRequestId, lastStepOrder);
        }
        return recipients;
    }

    private boolean hasEligibleApprovers(UUID approvalRequestId, int stepOrder) {
        List<Map<String, Object>> ctx = jdbcTemplate.queryForList("""
                SELECT s.approver_role_id AS role_id, ar.document_id, ar.requested_by
                  FROM approval_requests ar
                  JOIN approval_workflow_steps s
                    ON s.workflow_id = ar.workflow_id AND s.step_order = ?
                 WHERE ar.id = ?
                """,
                stepOrder,
                approvalRequestId);
        if (ctx.isEmpty()) {
            return false;
        }
        Map<String, Object> row = ctx.getFirst();
        UUID roleId = row.get("role_id") instanceof UUID u
                ? u
                : (row.get("role_id") != null ? UUID.fromString(String.valueOf(row.get("role_id"))) : null);
        if (roleId == null) {
            return false;
        }
        UUID documentId = row.get("document_id") instanceof UUID u
                ? u
                : UUID.fromString(String.valueOf(row.get("document_id")));
        UUID requesterId = row.get("requested_by") instanceof UUID u
                ? u
                : (row.get("requested_by") != null
                        ? UUID.fromString(String.valueOf(row.get("requested_by"))) : null);
        java.util.Set<UUID> contributors = FourEyesPolicy.loadContentContributors(jdbcTemplate, documentId);
        return approvalRoleResolver.resolveInScopeAssignees(roleId, documentId).stream()
                .anyMatch(id -> !FourEyesPolicy.isConflict(id, requesterId, contributors));
    }

    private UUID loadDocumentId(UUID approvalRequestId) {
        List<UUID> rows = jdbcTemplate.query("""
                SELECT document_id FROM approval_requests WHERE id = ?
                """,
                (rs, i) -> (UUID) rs.getObject("document_id"),
                approvalRequestId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.getFirst();
    }

    private UUID loadSpaceId(UUID documentId) {
        List<UUID> rows = jdbcTemplate.query("""
                SELECT space_id FROM documents WHERE id = ?
                """,
                (rs, i) -> (UUID) rs.getObject("space_id"),
                documentId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return rows.getFirst();
    }

    @Override
    @Transactional
    public String recordFinalDecision(
            UUID documentId,
            UUID approvalRequestId,
            int stepOrder,
            String decision,
            UUID actorId,
            String comment
    ) {
        log.info("Temporal recordFinalDecision request={} decision={} step={}",
                approvalRequestId, decision, stepOrder);

        // Défense : ne jamais publier comme approuvé un contenu muté pendant la revue.
        if ("approuve".equals(decision)
                && !submittedContentMatchesCurrent(documentId, approvalRequestId)) {
            return invalidateApprovalForContentDrift(documentId, approvalRequestId, stepOrder, actorId);
        }

        String requestStatus = "approuve".equals(decision) ? "approuve" : "rejete";
        String documentStatus = "approuve".equals(decision) ? "valide" : "brouillon";

        jdbcTemplate.update("""
                INSERT INTO approval_actions
                  (id, approval_request_id, step_order, actor_id, decision, comment, acted_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                """,
                UUID.randomUUID(), approvalRequestId, stepOrder, actorId, decision, comment);

        jdbcTemplate.update("""
                UPDATE approval_requests
                   SET status = ?, resolved_at = now(), sla_deadline_at = NULL
                 WHERE id = ? AND status = 'en_cours'
                """,
                requestStatus, approvalRequestId);

        jdbcTemplate.update(
                "UPDATE documents SET status = ?, updated_at = now() WHERE id = ?",
                documentStatus, documentId);

        String auditAction = "approuve".equals(decision)
                ? AuditActions.DOCUMENT_APPROVED
                : AuditActions.DOCUMENT_REJECTED;
        auditService.recordSync(
                actorId,
                false,
                auditAction,
                "document",
                documentId,
                Map.of(
                        "approvalRequestId", approvalRequestId.toString(),
                        "stepOrder", stepOrder,
                        "decision", decision,
                        "previousStatus", "en_revue",
                        "newStatus", documentStatus
                ),
                null
        );

        // Recalcul hors chemin critique : après commit TX, async.
        if ("approuve".equals(decision)) {
            reliabilityScoreService.requestRecalculationAfterCommit(documentId);
            if (activityEventService != null && actorId != null) {
                activityEventService.record(
                        ActivityEventTypes.PUBLICATION,
                        actorId,
                        documentId,
                        loadSpaceId(documentId),
                        Map.of(
                                "approvalRequestId", approvalRequestId.toString(),
                                "decision", decision));
            }
        } else {
            reliabilityScoreService.clearScoreInDb(documentId);
        }

        return requestStatus;
    }

    /**
     * True si le contenu courant est encore celui soumis (empreinte V38), ou si
     * l'empreinte est absente (demandes pré-V38 : pas de gate rétroactif).
     */
    private boolean submittedContentMatchesCurrent(UUID documentId, UUID approvalRequestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT ar.submitted_content_version_no AS submitted_ver,
                       ar.submitted_git_head_sha AS submitted_sha,
                       d.current_version_no AS current_ver,
                       d.git_head_sha AS current_sha
                  FROM approval_requests ar
                  JOIN documents d ON d.id = ar.document_id
                 WHERE ar.id = ? AND ar.document_id = ?
                """,
                approvalRequestId, documentId);
        if (rows.isEmpty()) {
            return true;
        }
        Map<String, Object> row = rows.getFirst();
        Object submittedVer = row.get("submitted_ver");
        if (submittedVer == null) {
            // Demande créée avant V38 — pas d'empreinte, laisse le chemin nominal.
            return true;
        }
        int submittedVersion = ((Number) submittedVer).intValue();
        int currentVersion = ((Number) row.get("current_ver")).intValue();
        if (submittedVersion != currentVersion) {
            return false;
        }
        String submittedSha = row.get("submitted_sha") == null
                ? null
                : String.valueOf(row.get("submitted_sha"));
        String currentSha = row.get("current_sha") == null
                ? null
                : String.valueOf(row.get("current_sha"));
        if (submittedSha == null && currentSha == null) {
            return true;
        }
        return submittedSha != null && submittedSha.equals(currentSha);
    }

    /**
     * Contenu divergé pendant la revue → demande annulée, document remis en brouillon,
     * audit + notification au demandeur. Ne publie jamais {@code valide}.
     */
    private String invalidateApprovalForContentDrift(
            UUID documentId,
            UUID approvalRequestId,
            int stepOrder,
            UUID actorId
    ) {
        log.warn("Approbation invalidée (contenu ≠ soumis) request={} document={}",
                approvalRequestId, documentId);

        jdbcTemplate.update("""
                UPDATE approval_requests
                   SET status = 'annule', resolved_at = now(), sla_deadline_at = NULL
                 WHERE id = ? AND status = 'en_cours'
                """,
                approvalRequestId);

        jdbcTemplate.update(
                "UPDATE documents SET status = 'brouillon', updated_at = now() WHERE id = ?",
                documentId);

        UUID requesterId = jdbcTemplate.queryForObject(
                "SELECT requested_by FROM approval_requests WHERE id = ?",
                UUID.class,
                approvalRequestId);

        Map<String, Object> auditMeta = new java.util.LinkedHashMap<>();
        auditMeta.put("approvalRequestId", approvalRequestId.toString());
        auditMeta.put("stepOrder", stepOrder);
        auditMeta.put("reason", "submitted_content_mismatch");
        auditMeta.put("previousStatus", "en_revue");
        auditMeta.put("newStatus", "brouillon");
        if (actorId != null) {
            auditMeta.put("attemptedBy", actorId.toString());
        }

        auditService.recordSync(
                actorId,
                false,
                AuditActions.DOCUMENT_APPROVAL_INVALIDATED,
                "document",
                documentId,
                auditMeta,
                null
        );

        if (requesterId != null) {
            String payloadJson = """
                    {"document_id":"%s","approval_request_id":"%s","message":"La demande d'approbation a été annulée : le contenu a été modifié pendant la revue."}
                    """.formatted(documentId, approvalRequestId).trim();
            try {
                jdbcTemplate.update("""
                        INSERT INTO notifications (id, user_id, type, payload, created_at)
                        VALUES (?, ?, 'approval_invalidated', CAST(? AS jsonb), now())
                        """,
                        UUID.randomUUID(), requesterId, payloadJson);
            } catch (Exception e) {
                log.error("Échec INSERT notification approval_invalidated user={} request={}",
                        requesterId, approvalRequestId, e);
                throw e;
            }
        }

        reliabilityScoreService.clearScoreInDb(documentId);
        return "annule";
    }

    @Override
    @Transactional
    public UUID ensureSystemActor() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ?",
                Integer.class,
                SYSTEM_ACTOR_ID);
        if (count == null || count == 0) {
            jdbcTemplate.update("""
                    INSERT INTO users (id, email, display_name, avatar_initials, status, is_system_account, created_at)
                    VALUES (?, 'system@socle.local', 'Système Socle', 'SY', 'active', true, now())
                    ON CONFLICT (id) DO NOTHING
                    """,
                    SYSTEM_ACTOR_ID);
        }
        return SYSTEM_ACTOR_ID;
    }

    private static UUID toUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof UUID u) {
            return u;
        }
        return UUID.fromString(String.valueOf(raw));
    }
}
