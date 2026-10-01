// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.storage.DocumentStore;
import eu.socle.template.TemplateBodySupport;
import eu.socle.user.UserSyncService;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "socle.temporal.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentApprovalService {

    public static final String TASK_QUEUE = "document-approval";

    private final WorkflowClient workflowClient;
    private final DocumentRepository documentRepository;
    private final UserSyncService userSyncService;
    private final AuthorizationService authorizationService;
    private final JdbcTemplate jdbcTemplate;
    private final ApprovalActivitiesImpl activities;
    private final ApprovalWorkflowDefinitionService workflowDefinitions;
    private final ApprovalRoleResolver approvalRoleResolver;
    private final TransactionTemplate transactionTemplate;
    private final DocumentStore documentStore;
    private final boolean workerEnabled;

    private WorkerFactory workerFactory;

    @Autowired
    public DocumentApprovalService(
            WorkflowClient workflowClient,
            DocumentRepository documentRepository,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            JdbcTemplate jdbcTemplate,
            ApprovalActivitiesImpl activities,
            ApprovalWorkflowDefinitionService workflowDefinitions,
            ApprovalRoleResolver approvalRoleResolver,
            PlatformTransactionManager transactionManager,
            DocumentStore documentStore,
            @Value("${socle.temporal.worker-enabled:true}") boolean workerEnabled
    ) {
        this.workflowClient = workflowClient;
        this.documentRepository = documentRepository;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.jdbcTemplate = jdbcTemplate;
        this.activities = activities;
        this.workflowDefinitions = workflowDefinitions;
        this.approvalRoleResolver = approvalRoleResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.documentStore = documentStore;
        this.workerEnabled = workerEnabled;
    }

    /** Constructeur de test — permet d'injecter un TransactionTemplate sans vrai TX manager. */
    DocumentApprovalService(
            WorkflowClient workflowClient,
            DocumentRepository documentRepository,
            UserSyncService userSyncService,
            AuthorizationService authorizationService,
            JdbcTemplate jdbcTemplate,
            ApprovalActivitiesImpl activities,
            ApprovalWorkflowDefinitionService workflowDefinitions,
            ApprovalRoleResolver approvalRoleResolver,
            TransactionTemplate transactionTemplate,
            boolean workerEnabled
    ) {
        this.workflowClient = workflowClient;
        this.documentRepository = documentRepository;
        this.userSyncService = userSyncService;
        this.authorizationService = authorizationService;
        this.jdbcTemplate = jdbcTemplate;
        this.activities = activities;
        this.workflowDefinitions = workflowDefinitions;
        this.approvalRoleResolver = approvalRoleResolver;
        this.transactionTemplate = transactionTemplate;
        this.documentStore = null;
        this.workerEnabled = workerEnabled;
    }

    @PostConstruct
    void startWorker() {
        if (!workerEnabled) {
            return;
        }
        workerFactory = WorkerFactory.newInstance(workflowClient);
        Worker worker = workerFactory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DocumentApprovalWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        workerFactory.start();
    }

    @PreDestroy
    void stopWorker() {
        if (workerFactory != null) {
            workerFactory.shutdown();
        }
    }

    /**
     * Démarre le workflow d'approbation.
     *
     * <p><b>Ordre imposé (auth → authz → workflow)</b> — audit intégration :
     * le Check OpenFGA ({@code requireDocumentRelation} + {@code requireSpaceRelation})
     * s'exécute <em>avant</em> tout {@code WorkflowClient.start(...)}.
     * Ainsi un JWT valide sans accès FGA à l'espace/document reçoit 403
     * et <em>aucun</em> workflow Temporal n'est créé (pas de start puis cancel).
     * Voir {@code docs/audit-integration-auth-authz-workflow.md}.
     */
    public ApprovalStartResponse startApproval(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);

        DocumentEntity document = documentRepository.findActiveById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"));

        // --- OpenFGA Check (DOIT rester avant WorkflowClient.start) ---
        authorizationService.requireDocumentRelation(user.getId(), documentId, "editor");
        authorizationService.requireSpaceRelation(user.getId(), document.getSpaceId(), "viewer");

        Map<String, Object> body = document.getBody();
        if (documentStore != null) {
            body = documentStore.readCurrentContent(documentId, body);
        }
        List<String> remaining = TemplateBodySupport.listPlaceholderHints(body);
        if (!remaining.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    TemplateBodySupport.placeholderConflictMessage(remaining));
        }

        Integer open = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE document_id = ? AND status = 'en_cours'",
                Integer.class,
                documentId);
        if (open != null && open > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une approbation est déjà en cours");
        }

        UUID requestId = UUID.randomUUID();
        String temporalWorkflowId = "doc-approval-" + documentId + "-" + requestId;
        UUID workflowDefId = workflowDefinitions.resolveId(document.getSpaceId(), document.getDocType());

        DocumentApprovalWorkflow workflow = workflowClient.newWorkflowStub(
                DocumentApprovalWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId(temporalWorkflowId)
                        .build());

        WorkflowClient.start(workflow::run, documentId, user.getId(), requestId, workflowDefId);

        // Laisser le worker écrire approval_requests ; court délai de lecture pour la réponse UI
        waitForRequestRow(requestId, 3_000);

        return new ApprovalStartResponse(requestId, temporalWorkflowId, "en_cours");
    }

    public ApprovalView currentApproval(Jwt jwt, UUID documentId) {
        var user = userSyncService.syncFromJwt(jwt);
        authorizationService.requireDocumentRelation(user.getId(), documentId, "viewer");

        List<ApprovalView> rows = jdbcTemplate.query("""
                SELECT ar.id, ar.document_id, d.title AS document_title,
                       ar.temporal_workflow_id, ar.status, ar.requested_by, ar.created_at,
                       ar.current_step_order, ar.sla_deadline_at, ar.submitted_version_no,
                       CASE
                         WHEN ar.submitted_version_no IS NOT NULL
                          AND ar.submitted_version_no > 1
                          AND EXISTS (
                            SELECT 1 FROM document_versions dv
                             WHERE dv.document_id = ar.document_id
                               AND dv.version_no = ar.submitted_version_no - 1
                          )
                         THEN ar.submitted_version_no - 1
                         ELSE NULL
                       END AS baseline_version_no
                  FROM approval_requests ar
                  JOIN documents d ON d.id = ar.document_id
                 WHERE ar.document_id = ? AND ar.status = 'en_cours'
                   AND d.deleted_at IS NULL
                 ORDER BY ar.created_at DESC
                 LIMIT 1
                """,
                (rs, i) -> mapApprovalView(rs),
                documentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Demandes en cours où l'utilisateur courant est l'approbateur de {@code current_step_order}
     * (rôle via {@code approval_workflow_steps.approver_role_id} / {@code approval_role_assignments}),
     * avec portée couvrant le document <strong>et</strong> accès OpenFGA {@code editor}.
     * Si l'étape n'a pas de rôle, fallback sur le rôle global « Éditeur de documents ».
     */
    public List<ApprovalView> listMine(Jwt jwt) {
        var user = userSyncService.syncFromJwt(jwt);
        List<Candidate> candidates = jdbcTemplate.query("""
                SELECT ar.id, ar.document_id, d.title AS document_title,
                       ar.temporal_workflow_id, ar.status, ar.requested_by, ar.created_at,
                       ar.current_step_order, ar.sla_deadline_at, ar.submitted_version_no,
                       CASE
                         WHEN ar.submitted_version_no IS NOT NULL
                          AND ar.submitted_version_no > 1
                          AND EXISTS (
                            SELECT 1 FROM document_versions dv
                             WHERE dv.document_id = ar.document_id
                               AND dv.version_no = ar.submitted_version_no - 1
                          )
                         THEN ar.submitted_version_no - 1
                         ELSE NULL
                       END AS baseline_version_no,
                       aws.approver_role_id
                  FROM approval_requests ar
                  JOIN documents d ON d.id = ar.document_id
                  JOIN approval_workflow_steps aws
                    ON aws.workflow_id = ar.workflow_id
                   AND aws.step_order = ar.current_step_order
                 WHERE ar.status = 'en_cours'
                   AND d.deleted_at IS NULL
                 ORDER BY ar.sla_deadline_at ASC NULLS LAST, ar.created_at ASC
                """,
                (rs, i) -> {
                    ApprovalView view = mapApprovalView(rs);
                    UUID roleId = (UUID) rs.getObject("approver_role_id");
                    return new Candidate(view, roleId);
                });

        return candidates.stream()
                .filter(c -> {
                    Set<UUID> contributors = FourEyesPolicy.loadContentContributors(
                            jdbcTemplate, c.view().documentId());
                    return !FourEyesPolicy.isConflict(
                            user.getId(), c.view().requestedBy(), contributors);
                })
                .filter(c -> approvalRoleResolver.canDecide(user.getId(), c.roleId(), c.view().documentId()))
                .filter(c -> authorizationService.hasRelation(
                        user.getId(), "document", c.view().documentId(), "editor"))
                .map(Candidate::view)
                .toList();
    }

    private record Candidate(ApprovalView view, UUID roleId) {}

    /**
     * Signale une décision Temporal après verrouillage ligne ({@code FOR UPDATE}) et
     * contrôle atomique de {@code expectedStepOrder} vs {@code current_step_order}.
     * Aucun signal n'est envoyé si l'étape a changé ou si la demande est déjà résolue.
     */
    public Map<String, String> decide(Jwt jwt, UUID documentId, UUID requestId, DecisionRequest body) {
        var user = userSyncService.syncFromJwt(jwt);
        if (!authorizationService.hasRelation(user.getId(), "document", documentId, "editor")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pas d'accès à la page");
        }

        String decision = body.decision().trim().toLowerCase();
        if (!"approuve".equals(decision) && !"rejete".equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision doit être approuve|rejete");
        }
        if (body.expectedStepOrder() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedStepOrder requis");
        }
        int expectedStep = body.expectedStepOrder();

        String temporalWorkflowId = transactionTemplate.execute(status -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT ar.temporal_workflow_id, ar.status, ar.document_id, ar.current_step_order,
                           ar.requested_by, ar.submitted_version_no
                      FROM approval_requests ar
                     WHERE ar.id = ?
                     FOR UPDATE OF ar
                    """, requestId);
            if (rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande d'approbation introuvable");
            }
            Map<String, Object> row = rows.getFirst();
            UUID rowDocId = row.get("document_id") instanceof UUID u
                    ? u
                    : UUID.fromString(String.valueOf(row.get("document_id")));
            if (!documentId.equals(rowDocId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Demande hors document");
            }
            if (!"en_cours".equals(row.get("status"))) {
                throw ApprovalConflictException.alreadyResolved();
            }
            int actualStep = ((Number) row.get("current_step_order")).intValue();
            if (actualStep != expectedStep) {
                throw ApprovalConflictException.stepAdvanced(expectedStep, actualStep);
            }

            UUID requestedBy = toUuid(row.get("requested_by"));
            Set<UUID> contributors = FourEyesPolicy.loadContentContributors(jdbcTemplate, documentId);
            if (FourEyesPolicy.isConflict(user.getId(), requestedBy, contributors)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "séparation des tâches : vous ne pouvez pas approuver votre propre demande");
            }

            if (!approvalRoleResolver.canDecideCurrentStep(user.getId(), requestId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "rôle hors portée");
            }

            String wfId = (String) row.get("temporal_workflow_id");
            DocumentApprovalWorkflow stub = workflowClient.newWorkflowStub(
                    DocumentApprovalWorkflow.class,
                    wfId);
            // Signal sous le même verrou ligne : une escalade concurrente (FOR UPDATE)
            // attend la fin de cette transaction — pas de race check→signal.
            stub.decide(decision, user.getId(), body.comment());
            return wfId;
        });

        waitForResolved(requestId, 5_000);

        String resolvedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM approval_requests WHERE id = ?",
                String.class,
                requestId);
        return Map.of(
                "approvalRequestId", requestId.toString(),
                "temporalWorkflowId", temporalWorkflowId != null ? temporalWorkflowId : "",
                "status", resolvedStatus != null ? resolvedStatus : decision
        );
    }

    /**
     * Quatre yeux : le demandeur et les contributeurs de contenu ne peuvent pas décider.
     * @deprecated utiliser {@link FourEyesPolicy#isConflict}
     */
    @Deprecated
    static boolean isFourEyesConflict(UUID actorId, UUID requestedBy, UUID submittedAuthorId) {
        java.util.Set<UUID> contributors = submittedAuthorId == null
                ? java.util.Set.of()
                : java.util.Set.of(submittedAuthorId);
        return FourEyesPolicy.isConflict(actorId, requestedBy, contributors);
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

    private ApprovalView mapApprovalView(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer submitted = (Integer) rs.getObject("submitted_version_no");
        Integer baseline = (Integer) rs.getObject("baseline_version_no");
        Timestamp sla = rs.getTimestamp("sla_deadline_at");
        Timestamp created = rs.getTimestamp("created_at");
        return new ApprovalView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("document_id"),
                rs.getString("document_title"),
                rs.getString("temporal_workflow_id"),
                rs.getString("status"),
                rs.getInt("current_step_order"),
                sla != null ? sla.toInstant().toString() : null,
                submitted,
                baseline,
                (UUID) rs.getObject("requested_by"),
                created != null ? created.toInstant().toString() : null
        );
    }

    private void waitForRequestRow(UUID requestId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM approval_requests WHERE id = ?",
                    Integer.class,
                    requestId);
            if (count != null && count > 0) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void waitForResolved(UUID requestId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM approval_requests WHERE id = ?",
                    String.class,
                    requestId);
            // Résolu, ou alerte chaîne épuisée (en_cours mais sla_deadline_at NULL)
            if (status != null && !"en_cours".equals(status)) {
                return;
            }
            Boolean exhausted = jdbcTemplate.queryForObject("""
                    SELECT (status = 'en_cours' AND sla_deadline_at IS NULL)
                      FROM approval_requests WHERE id = ?
                    """,
                    Boolean.class,
                    requestId);
            if (Boolean.TRUE.equals(exhausted)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public record ApprovalStartResponse(UUID approvalRequestId, String temporalWorkflowId, String status) {}

    public record ApprovalView(
            UUID approvalRequestId,
            UUID documentId,
            String documentTitle,
            String temporalWorkflowId,
            String status,
            int currentStepOrder,
            String slaDeadlineAt,
            Integer submittedVersionNo,
            Integer baselineVersionNo,
            UUID requestedBy,
            String createdAt
    ) {}

    public record DecisionRequest(
            @NotBlank String decision,
            String comment,
            @NotNull Integer expectedStepOrder
    ) {}
}
