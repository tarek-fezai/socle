package eu.socle.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.storage.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
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
    private final DocumentStore documentStore;
    private final ObjectMapper objectMapper;
    private final ApprovalRoleResolver approvalRoleResolver;

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
                SELECT current_version_no, body, git_head_sha
                  FROM documents
                 WHERE id = ? AND deleted_at IS NULL
                """, documentId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Document introuvable pour soumission: " + documentId);
        }
        Map<String, Object> docRow = rows.getFirst();
        int submittedVersionNo = ((Number) docRow.get("current_version_no")).intValue();
        Map<String, Object> body = parseBody(docRow.get("body"));
        String expectedHead = docRow.get("git_head_sha") == null
                ? null
                : String.valueOf(docRow.get("git_head_sha"));

        // Archive + bump via DocumentStore (commit Git si provider=git) — pas d'INSERT JDBC direct.
        documentStore.archiveVersion(
                documentId, submittedVersionNo, body, requesterId, "Soumission pour approbation");
        String newHead = documentStore.writeCurrentContent(
                documentId, body, requesterId, "Soumission pour approbation", expectedHead);

        jdbcTemplate.update("""
                UPDATE documents
                   SET status = 'en_revue',
                       current_version_no = current_version_no + 1,
                       git_head_sha = COALESCE(?, git_head_sha),
                       updated_at = now()
                 WHERE id = ? AND deleted_at IS NULL
                """,
                newHead, documentId);

        jdbcTemplate.update("""
                INSERT INTO approval_requests
                  (id, document_id, workflow_id, temporal_workflow_id, requested_by,
                   current_step_order, status, sla_deadline_at, created_at, submitted_version_no)
                VALUES (?, ?, ?, ?, ?, ?, 'en_cours', now() + make_interval(hours => ?), now(), ?)
                ON CONFLICT (id) DO NOTHING
                """,
                approvalRequestId, documentId, workflowDefId, temporalWorkflowId, requesterId,
                firstStepOrder, firstStepSlaHours, submittedVersionNo);

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
     * Demandeur + utilisateurs du rôle approbateur de la dernière étape
     * couverts par la portée du document ({@code approval_role_assignments}).
     */
    private List<UUID> resolveChainExhaustedRecipients(
            UUID approvalRequestId,
            int lastStepOrder,
            UUID requesterId
    ) {
        List<UUID> recipients = new java.util.ArrayList<>();
        recipients.add(requesterId);

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
                    if (!recipients.contains(approverId)) {
                        recipients.add(approverId);
                    }
                }
            } else {
                log.warn("Chaîne épuisée request={} : aucun rôle sur l'étape {} — notification demandeur seule",
                        approvalRequestId, lastStepOrder);
            }
        }

        if (recipients.size() == 1) {
            log.warn("Chaîne épuisée request={} : aucun approbateur in-scope pour l'étape {} — notification demandeur seule",
                    approvalRequestId, lastStepOrder);
        }
        return recipients;
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
        } else {
            reliabilityScoreService.clearScoreInDb(documentId);
        }

        return requestStatus;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            return new HashMap<>((Map<String, Object>) map);
        }
        try {
            String json = raw.toString();
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("body document illisible pour soumission", e);
        }
    }
}
