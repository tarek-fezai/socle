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

    /**
     * Version de référence pour le diff d'approbation = dernière
     * {@code submitted_version_no} d'une demande {@code approuve} sur le même document
     * (schéma actuel et ancien). {@code NULL} s'il n'y a jamais eu d'approbation.
     */
    static final String BASELINE_VERSION_SQL = """
            (
              SELECT prev.submitted_version_no
                FROM approval_requests prev
               WHERE prev.document_id = ar.document_id
                 AND prev.status = 'approuve'
                 AND prev.submitted_version_no IS NOT NULL
               ORDER BY prev.resolved_at DESC NULLS LAST, prev.created_at DESC
               LIMIT 1
            ) AS baseline_version_no
            """;

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
    private final DocumentRelatedLinksService relatedLinksService;
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
            DocumentRelatedLinksService relatedLinksService,
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
        this.relatedLinksService = relatedLinksService;
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
        this(
                workflowClient, documentRepository, userSyncService, authorizationService,
                jdbcTemplate, activities, workflowDefinitions, approvalRoleResolver,
                transactionTemplate, null, workerEnabled);
    }

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
            DocumentRelatedLinksService relatedLinksService,
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
        this.relatedLinksService = relatedLinksService;
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

        List<ApprovalView> rows = jdbcTemplate.query(
                """
                SELECT ar.id, ar.document_id, d.title AS document_title,
                       ar.temporal_workflow_id, ar.status, ar.requested_by, ar.created_at,
                       ar.current_step_order, ar.sla_deadline_at, ar.submitted_version_no,
                       u.display_name AS requested_by_display_name,
                       u.avatar_initials AS requested_by_initials,
                """
                        + BASELINE_VERSION_SQL
                        + """
                  FROM approval_requests ar
                  JOIN documents d ON d.id = ar.document_id
                  LEFT JOIN users u ON u.id = ar.requested_by
                 WHERE ar.document_id = ? AND ar.status = 'en_cours'
                   AND d.deleted_at IS NULL
                 ORDER BY ar.created_at DESC
                 LIMIT 1
                """,
                (rs, i) -> mapApprovalView(rs),
                documentId);
        if (rows.isEmpty()) {
            return null;
        }
        // Pas de liens impactés ici : détail = GET /api/v1/approvals/{requestId}.
        return rows.getFirst();
    }

    /**
     * Détail d'une demande pour tout viewer du document : liens impactés (entrants filtrés)
     * + {@code canDecide} (quatre yeux + rôle d'étape + editor). Non-viewer → 404.
     */
    public ApprovalDetailView getApproval(Jwt jwt, UUID requestId) {
        var user = userSyncService.syncFromJwt(jwt);
        List<ApprovalView> rows = jdbcTemplate.query(
                """
                SELECT ar.id, ar.document_id, d.title AS document_title,
                       ar.temporal_workflow_id, ar.status, ar.requested_by, ar.created_at,
                       ar.current_step_order, ar.sla_deadline_at, ar.submitted_version_no,
                       u.display_name AS requested_by_display_name,
                       u.avatar_initials AS requested_by_initials,
                """
                        + BASELINE_VERSION_SQL
                        + """
                  FROM approval_requests ar
                  JOIN documents d ON d.id = ar.document_id
                  LEFT JOIN users u ON u.id = ar.requested_by
                 WHERE ar.id = ?
                   AND d.deleted_at IS NULL
                """,
                (rs, i) -> mapApprovalView(rs),
                requestId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande d'approbation introuvable");
        }
        ApprovalView base = rows.getFirst();
        if (!authorizationService.hasRelation(user.getId(), "document", base.documentId(), "viewer")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande d'approbation introuvable");
        }

        ApprovalView withLinks = enrichImpactedLinks(jwt, base);
        DecideAbility ability = resolveDecideAbility(user.getId(), withLinks);
        return toDetail(withLinks, ability);
    }

    /**
     * Demandes en cours où l'utilisateur courant est l'approbateur de {@code current_step_order}
     * (rôle via {@code approval_workflow_steps.approver_role_id} / {@code approval_role_assignments}),
     * avec portée couvrant le document <strong>et</strong> accès OpenFGA {@code editor}.
     * Si l'étape n'a pas de rôle, fallback sur le rôle global « Éditeur de documents ».
     * File « à décider » uniquement — pas de calcul de liens impactés (détail à part).
     */
    public List<ApprovalView> listMine(Jwt jwt) {
        var user = userSyncService.syncFromJwt(jwt);
        List<Candidate> candidates = jdbcTemplate.query(
                """
                SELECT ar.id, ar.document_id, d.title AS document_title,
                       ar.temporal_workflow_id, ar.status, ar.requested_by, ar.created_at,
                       ar.current_step_order, ar.sla_deadline_at, ar.submitted_version_no,
                       u.display_name AS requested_by_display_name,
                       u.avatar_initials AS requested_by_initials,
                """
                        + BASELINE_VERSION_SQL
                        + """
                       ,
                       aws.approver_role_id
                  FROM approval_requests ar
                  JOIN documents d ON d.id = ar.document_id
                  JOIN approval_workflow_steps aws
                    ON aws.workflow_id = ar.workflow_id
                   AND aws.step_order = ar.current_step_order
                  LEFT JOIN users u ON u.id = ar.requested_by
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

    private record DecideAbility(boolean canDecide, String cannotDecideReason) {}

    private DecideAbility resolveDecideAbility(UUID userId, ApprovalView view) {
        if (!"en_cours".equals(view.status())) {
            return new DecideAbility(false, "resolved");
        }
        if (userId.equals(view.requestedBy())) {
            return new DecideAbility(false, "requester");
        }
        Set<UUID> contributors = FourEyesPolicy.loadContentContributors(jdbcTemplate, view.documentId());
        if (contributors.contains(userId)) {
            return new DecideAbility(false, "contributor");
        }
        if (!approvalRoleResolver.canDecideCurrentStep(userId, view.approvalRequestId())
                || !authorizationService.hasRelation(userId, "document", view.documentId(), "editor")) {
            return new DecideAbility(false, "not_current_step_approver");
        }
        return new DecideAbility(true, null);
    }

    private static ApprovalDetailView toDetail(ApprovalView v, DecideAbility ability) {
        return new ApprovalDetailView(
                v.approvalRequestId(),
                v.documentId(),
                v.documentTitle(),
                v.temporalWorkflowId(),
                v.status(),
                v.currentStepOrder(),
                v.slaDeadlineAt(),
                v.submittedVersionNo(),
                v.baselineVersionNo(),
                v.requestedBy(),
                v.createdAt(),
                v.requestedByDisplayName(),
                v.requestedByInitials(),
                v.impactedLinks(),
                v.hiddenImpactedCount(),
                ability.canDecide(),
                ability.cannotDecideReason());
    }

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
        if ("rejete".equals(decision)
                && (body.comment() == null || body.comment().isBlank())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Justification obligatoire pour un refus");
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
                created != null ? created.toInstant().toString() : null,
                rs.getString("requested_by_display_name"),
                rs.getString("requested_by_initials"),
                List.of(),
                0
        );
    }

    /**
     * Liens <em>entrants</em> visibles (documents qui pointent vers le document approuvé),
     * filtrés BatchCheck viewer — jamais de titre/id non lisible ; {@code hiddenImpactedCount}
     * pour les sources inaccessibles.
     */
    private ApprovalView enrichImpactedLinks(Jwt jwt, ApprovalView view) {
        if (relatedLinksService == null || view == null) {
            return view;
        }
        try {
            var impacted = relatedLinksService.impactedIncoming(jwt, view.documentId());
            List<ImpactedLink> links = impacted.visible().stream()
                    .map(l -> new ImpactedLink(l.id(), l.title()))
                    .toList();
            return new ApprovalView(
                    view.approvalRequestId(),
                    view.documentId(),
                    view.documentTitle(),
                    view.temporalWorkflowId(),
                    view.status(),
                    view.currentStepOrder(),
                    view.slaDeadlineAt(),
                    view.submittedVersionNo(),
                    view.baselineVersionNo(),
                    view.requestedBy(),
                    view.createdAt(),
                    view.requestedByDisplayName(),
                    view.requestedByInitials(),
                    links,
                    impacted.hiddenCount());
        } catch (ResponseStatusException e) {
            return view;
        }
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

    public record ImpactedLink(UUID id, String title) {}

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
            String createdAt,
            String requestedByDisplayName,
            String requestedByInitials,
            List<ImpactedLink> impactedLinks,
            int hiddenImpactedCount
    ) {
        public ApprovalView {
            if (impactedLinks == null) {
                impactedLinks = List.of();
            }
        }
    }

    /**
     * Détail lecture/décision : {@code cannotDecideReason} ∈
     * {@code requester|contributor|not_current_step_approver|resolved} (null si {@code canDecide}).
     */
    public record ApprovalDetailView(
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
            String createdAt,
            String requestedByDisplayName,
            String requestedByInitials,
            List<ImpactedLink> impactedLinks,
            int hiddenImpactedCount,
            boolean canDecide,
            String cannotDecideReason
    ) {
        public ApprovalDetailView {
            if (impactedLinks == null) {
                impactedLinks = List.of();
            }
        }
    }

    public record DecisionRequest(
            @NotBlank String decision,
            String comment,
            @NotNull Integer expectedStepOrder
    ) {}
}
