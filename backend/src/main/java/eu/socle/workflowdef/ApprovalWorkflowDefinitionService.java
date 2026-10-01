// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.workflowdef;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditActorResolver;
import eu.socle.audit.AuditService;
import eu.socle.workflowdef.WorkflowDefinitionDtos.ApplicableView;
import eu.socle.workflowdef.WorkflowDefinitionDtos.DefinitionView;
import eu.socle.workflowdef.WorkflowDefinitionDtos.GlobalRoleView;
import eu.socle.workflowdef.WorkflowDefinitionDtos.StepInput;
import eu.socle.workflowdef.WorkflowDefinitionDtos.StepView;
import eu.socle.workflowdef.WorkflowDefinitionDtos.UpsertRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD des définitions d'approbation + résolution par scope à la soumission.
 *
 * <p>Ne touche pas au moteur Temporal : choisit seulement quel {@code workflow_id}
 * (liste d'{@code ApprovalStepDef}) est passé au démarrage.
 *
 * <p>Règle de précédence — voir {@code docs/workflow-sla-escalade.md} § Sélection.
 */
@Service
public class ApprovalWorkflowDefinitionService {

    public static final String DEFAULT_WORKFLOW_NAME = "Approbation simple";
    public static final String DEFAULT_APPROVER_ROLE = "Éditeur de documents";

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final AuditActorResolver auditActorResolver;

    public ApprovalWorkflowDefinitionService(
            JdbcTemplate jdbc,
            AuditService auditService,
            AuditActorResolver auditActorResolver
    ) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.auditActorResolver = auditActorResolver;
    }

    @Transactional(readOnly = true)
    public List<DefinitionView> list() {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM approval_workflows ORDER BY created_at ASC, name ASC",
                (rs, i) -> (UUID) rs.getObject("id"));
        return ids.stream().map(this::requireView).toList();
    }

    @Transactional(readOnly = true)
    public DefinitionView get(UUID id) {
        return requireView(id);
    }

    @Transactional
    public DefinitionView create(UpsertRequest request) {
        validateUpsert(request, null);
        UUID id = UUID.randomUUID();
        String docType = blankToNull(request.scopeDocType());
        jdbc.update("""
                INSERT INTO approval_workflows (id, name, scope_space_id, scope_doc_type, status, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """,
                id,
                request.name().trim(),
                request.scopeSpaceId(),
                docType,
                request.status().trim());
        insertSteps(id, request.steps());
        DefinitionView created = requireView(id);
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.WORKFLOW_CREATED,
                "workflow",
                id,
                Map.of(
                        "name", created.name(),
                        "status", created.status(),
                        "stepCount", created.steps().size()
                ),
                null);
        return created;
    }

    @Transactional
    public DefinitionView update(UUID id, UpsertRequest request) {
        requireExists(id);
        assertNotInUse(id);
        validateUpsert(request, id);
        String docType = blankToNull(request.scopeDocType());
        int updated = jdbc.update("""
                UPDATE approval_workflows
                   SET name = ?, scope_space_id = ?, scope_doc_type = ?, status = ?
                 WHERE id = ?
                """,
                request.name().trim(),
                request.scopeSpaceId(),
                docType,
                request.status().trim(),
                id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow introuvable");
        }
        jdbc.update("DELETE FROM approval_workflow_steps WHERE workflow_id = ?", id);
        insertSteps(id, request.steps());
        DefinitionView updatedView = requireView(id);
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.WORKFLOW_UPDATED,
                "workflow",
                id,
                Map.of(
                        "name", updatedView.name(),
                        "status", updatedView.status(),
                        "stepCount", updatedView.steps().size()
                ),
                null);
        return updatedView;
    }

    @Transactional
    public void delete(UUID id) {
        requireExists(id);
        assertNotInUse(id);
        // Les instances historiques (terminées) gardent la FK : on refuse aussi s'il reste
        // des requests (même terminées) pour ne pas casser l'historique — soft : status draft?
        // Critère produit : bloquer si en_cours ; pour le reste, ON DELETE non CASCADE sur
        // approval_requests → supprimer seulement si zéro request liée.
        Integer any = jdbc.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE workflow_id = ?",
                Integer.class,
                id);
        if (any != null && any > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Workflow déjà utilisé par des demandes d'approbation — suppression impossible");
        }
        jdbc.update("DELETE FROM approval_workflows WHERE id = ?", id);
        auditService.record(
                auditActorResolver.currentUserId().orElse(null),
                false,
                AuditActions.WORKFLOW_DELETED,
                "workflow",
                id,
                Map.of(),
                null);
    }

    /**
     * Résout l'id de définition applicable (toujours non-null — fallback seed).
     */
    @Transactional
    public UUID resolveId(UUID spaceId, String docType) {
        return resolveApplicable(spaceId, docType).id();
    }

    @Transactional
    public ApplicableView resolveApplicable(UUID spaceId, String docType) {
        Candidate best = findBestCandidate(spaceId, blankToNull(docType));
        if (best != null) {
            DefinitionView view = requireView(best.id());
            return new ApplicableView(
                    view.id(),
                    view.name(),
                    view.steps().size(),
                    best.matchLevel(),
                    view.steps());
        }
        UUID fallbackId = ensureDefaultWorkflow();
        DefinitionView view = requireView(fallbackId);
        return new ApplicableView(
                view.id(),
                view.name(),
                view.steps().size(),
                "fallback",
                view.steps());
    }

    @Transactional(readOnly = true)
    public List<GlobalRoleView> listGlobalRoles() {
        return jdbc.query("""
                SELECT id, name, description FROM global_roles ORDER BY name ASC
                """,
                (rs, i) -> new GlobalRoleView(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        rs.getString("description")));
    }

    /**
     * Garantit le seed « Approbation simple » (1 étape, 24h, scopes NULL) — jamais remplacé.
     */
    @Transactional
    public UUID ensureDefaultWorkflow() {
        UUID existing = jdbc.query(
                "SELECT id FROM approval_workflows WHERE name = ? LIMIT 1",
                rs -> rs.next() ? (UUID) rs.getObject("id") : null,
                DEFAULT_WORKFLOW_NAME);
        if (existing != null) {
            return existing;
        }
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO approval_workflows (id, name, scope_space_id, scope_doc_type, status, created_at)
                VALUES (?, ?, NULL, NULL, 'active', now())
                """,
                id, DEFAULT_WORKFLOW_NAME);
        UUID editorRoleId = jdbc.query(
                "SELECT id FROM global_roles WHERE name = ? LIMIT 1",
                rs -> rs.next() ? (UUID) rs.getObject("id") : null,
                DEFAULT_APPROVER_ROLE);
        jdbc.update("""
                INSERT INTO approval_workflow_steps
                  (id, workflow_id, step_order, sla_hours, approver_role_id, escalates_to_step_order)
                VALUES (?, ?, 1, 24, ?, NULL)
                """,
                UUID.randomUUID(), id, editorRoleId);
        return id;
    }

    // --- sélection ---

    private Candidate findBestCandidate(UUID spaceId, String docType) {
        List<RawWorkflow> active = jdbc.query("""
                SELECT id, name, scope_space_id, scope_doc_type, created_at
                  FROM approval_workflows
                 WHERE status = 'active'
                """,
                (rs, i) -> new RawWorkflow(
                        (UUID) rs.getObject("id"),
                        rs.getString("name"),
                        (UUID) rs.getObject("scope_space_id"),
                        rs.getString("scope_doc_type"),
                        rs.getTimestamp("created_at")));

        List<Candidate> matched = new ArrayList<>();
        for (RawWorkflow w : active) {
            String level = matchLevel(w, spaceId, docType);
            if (level != null) {
                matched.add(new Candidate(w.id(), level, specificityRank(level), w.createdAt(), w.name()));
            }
        }
        if (matched.isEmpty()) {
            return null;
        }
        matched.sort(Comparator
                .comparingInt(Candidate::rank).reversed()
                .thenComparing(Candidate::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Candidate::name, Comparator.nullsLast(String::compareToIgnoreCase)));
        return matched.getFirst();
    }

    /**
     * @return niveau de match, ou {@code null} si la définition n'est pas éligible
     */
    static String matchLevel(RawWorkflow w, UUID spaceId, String docType) {
        boolean spaceScoped = w.scopeSpaceId() != null;
        boolean typeScoped = w.scopeDocType() != null && !w.scopeDocType().isBlank();

        boolean spaceOk = !spaceScoped || Objects.equals(w.scopeSpaceId(), spaceId);
        boolean typeOk = !typeScoped || (docType != null
                && w.scopeDocType().trim().equalsIgnoreCase(docType.trim()));

        if (!spaceOk || !typeOk) {
            return null;
        }

        if (spaceScoped && typeScoped) {
            return "space_type";
        }
        if (spaceScoped) {
            return "space";
        }
        if (typeScoped) {
            return "type";
        }
        return "global";
    }

    static int specificityRank(String level) {
        return switch (level) {
            case "space_type" -> 4;
            case "space" -> 3;
            case "type" -> 2;
            case "global" -> 1;
            default -> 0;
        };
    }

    // --- validation / persistence ---

    void validateUpsert(UpsertRequest request, UUID updatingId) {
        String status = request.status() == null ? "" : request.status().trim();
        if (!status.equals("active") && !status.equals("draft")) {
            throw badRequest("status doit être active|draft");
        }
        if (request.scopeSpaceId() != null) {
            Integer spaces = jdbc.queryForObject(
                    "SELECT count(*) FROM spaces WHERE id = ?", Integer.class, request.scopeSpaceId());
            if (spaces == null || spaces == 0) {
                throw badRequest("scope_space_id : espace introuvable");
            }
        }
        List<StepInput> steps = request.steps();
        if (steps == null || steps.isEmpty()) {
            throw badRequest("au moins une étape est requise");
        }
        Set<Integer> orders = new HashSet<>();
        for (StepInput step : steps) {
            if (step.stepOrder() == null || step.stepOrder() < 1) {
                throw badRequest("step_order doit être un entier ≥ 1");
            }
            if (!orders.add(step.stepOrder())) {
                throw badRequest("step_order en double : " + step.stepOrder());
            }
            if (step.slaHours() != null && step.slaHours() <= 0) {
                throw badRequest("sla_hours doit être > 0 (ou null = défaut 24h)");
            }
            if (step.approverRoleId() != null) {
                Integer roles = jdbc.queryForObject(
                        "SELECT count(*) FROM global_roles WHERE id = ?",
                        Integer.class,
                        step.approverRoleId());
                if (roles == null || roles == 0) {
                    throw badRequest("approver_role_id introuvable : " + step.approverRoleId());
                }
            }
        }
        for (int expected = 1; expected <= steps.size(); expected++) {
            if (!orders.contains(expected)) {
                throw badRequest("step_order doivent être contigus de 1 à " + steps.size()
                        + " (manque " + expected + ")");
            }
        }
        for (StepInput step : steps) {
            Integer target = step.escalatesToStepOrder();
            if (target != null && !orders.contains(target)) {
                throw badRequest("escalates_to_step_order " + target
                        + " ne référence aucune étape de cette définition");
            }
        }
        // Nom unique (hors soi-même) — le seed « Approbation simple » doit rester unique
        Integer dup = jdbc.queryForObject("""
                SELECT count(*) FROM approval_workflows
                 WHERE lower(name) = lower(?) AND (?::uuid IS NULL OR id <> ?)
                """,
                Integer.class,
                request.name().trim(),
                updatingId,
                updatingId);
        if (dup != null && dup > 0) {
            throw badRequest("un workflow nommé « " + request.name().trim() + " » existe déjà");
        }
        if ("active".equals(status)) {
            assertNoActiveScopeCollision(request.scopeSpaceId(), blankToNull(request.scopeDocType()), updatingId);
        }
    }

    /**
     * Au plus une définition {@code active} par couple (espace, type) — NULL = « tous ».
     * Évite deux définitions au même niveau de précédence (ex æquo silencieux).
     */
    private void assertNoActiveScopeCollision(UUID scopeSpaceId, String scopeDocType, UUID updatingId) {
        Integer collision = jdbc.queryForObject("""
                SELECT count(*) FROM approval_workflows
                 WHERE status = 'active'
                   AND (?::uuid IS NULL OR id <> ?)
                   AND scope_space_id IS NOT DISTINCT FROM ?
                   AND lower(coalesce(trim(scope_doc_type), ''))
                       = lower(coalesce(trim(?), ''))
                """,
                Integer.class,
                updatingId,
                updatingId,
                scopeSpaceId,
                scopeDocType);
        if (collision != null && collision > 0) {
            throw badRequest(
                    "une définition active existe déjà pour ce scope "
                            + "(espace=" + (scopeSpaceId == null ? "tous" : scopeSpaceId)
                            + ", type=" + (scopeDocType == null ? "tous" : scopeDocType) + ")");
        }
    }

    private void insertSteps(UUID workflowId, List<StepInput> steps) {
        List<StepInput> ordered = steps.stream()
                .sorted(Comparator.comparingInt(StepInput::stepOrder))
                .toList();
        for (StepInput step : ordered) {
            jdbc.update("""
                    INSERT INTO approval_workflow_steps
                      (id, workflow_id, step_order, sla_hours, approver_role_id, escalates_to_step_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    workflowId,
                    step.stepOrder(),
                    step.slaHours(),
                    step.approverRoleId(),
                    step.escalatesToStepOrder());
        }
    }

    private void assertNotInUse(UUID id) {
        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE workflow_id = ? AND status = 'en_cours'",
                Integer.class,
                id);
        if (open != null && open > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Workflow utilisé par " + open
                            + " demande(s) en cours — modification / suppression structurelle bloquée "
                            + "(pas de versioning ; attend la fin des instances)");
        }
    }

    private void requireExists(UUID id) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM approval_workflows WHERE id = ?", Integer.class, id);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow introuvable");
        }
    }

    private DefinitionView requireView(UUID id) {
        List<DefinitionView> headers = jdbc.query("""
                SELECT id, name, scope_space_id, scope_doc_type, status, created_at
                  FROM approval_workflows WHERE id = ?
                """,
                (rs, i) -> {
                    UUID wid = (UUID) rs.getObject("id");
                    Timestamp created = rs.getTimestamp("created_at");
                    return new DefinitionView(
                            wid,
                            rs.getString("name"),
                            (UUID) rs.getObject("scope_space_id"),
                            rs.getString("scope_doc_type"),
                            rs.getString("status"),
                            created != null ? created.toInstant().toString() : null,
                            List.of(),
                            0);
                },
                id);
        if (headers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow introuvable");
        }
        DefinitionView header = headers.getFirst();
        List<StepView> steps = jdbc.query("""
                SELECT s.id, s.step_order, s.sla_hours, s.approver_role_id, s.escalates_to_step_order,
                       gr.name AS role_name
                  FROM approval_workflow_steps s
                  LEFT JOIN global_roles gr ON gr.id = s.approver_role_id
                 WHERE s.workflow_id = ?
                 ORDER BY s.step_order ASC
                """,
                (rs, i) -> new StepView(
                        (UUID) rs.getObject("id"),
                        rs.getInt("step_order"),
                        (Integer) rs.getObject("sla_hours"),
                        (UUID) rs.getObject("approver_role_id"),
                        rs.getString("role_name"),
                        (Integer) rs.getObject("escalates_to_step_order")),
                id);
        Integer inProgress = jdbc.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE workflow_id = ? AND status = 'en_cours'",
                Integer.class,
                id);
        return new DefinitionView(
                header.id(),
                header.name(),
                header.scopeSpaceId(),
                header.scopeDocType(),
                header.status(),
                header.createdAt(),
                steps,
                inProgress == null ? 0 : inProgress);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    record RawWorkflow(UUID id, String name, UUID scopeSpaceId, String scopeDocType, Timestamp createdAt) {}

    record Candidate(UUID id, String matchLevel, int rank, Timestamp createdAt, String name) {}
}
