// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.workflowdef;

import eu.socle.workflowdef.ApprovalWorkflowDefinitionService.RawWorkflow;
import eu.socle.workflowdef.WorkflowDefinitionDtos.ApplicableView;
import eu.socle.workflowdef.WorkflowDefinitionDtos.DefinitionView;
import eu.socle.workflowdef.WorkflowDefinitionDtos.StepInput;
import eu.socle.workflowdef.WorkflowDefinitionDtos.UpsertRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * CRUD définitions + validations + sélection par scope + verrou en_cours.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class ApprovalWorkflowDefinitionServiceTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("socle_core")
            .withUsername("socle")
            .withPassword("socle");

    static JdbcTemplate jdbc;
    static ApprovalWorkflowDefinitionService service;

    static final UUID SPACE_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID SPACE_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID ROLE_EDITOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    static final UUID ROLE_ANALYST = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void schema() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        service = new ApprovalWorkflowDefinitionService(jdbc, mock(eu.socle.audit.AuditService.class), mock(eu.socle.audit.AuditActorResolver.class));

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbc.execute("CREATE TABLE spaces (id UUID PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE users (
                  id UUID PRIMARY KEY, email TEXT NOT NULL, display_name TEXT NOT NULL, status TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE global_roles (
                  id UUID PRIMARY KEY, name TEXT NOT NULL, description TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE documents (
                  id UUID PRIMARY KEY, space_id UUID NOT NULL REFERENCES spaces(id),
                  title TEXT NOT NULL, body JSONB NOT NULL DEFAULT '{}', status TEXT NOT NULL,
                  doc_type TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_workflows (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  name TEXT NOT NULL,
                  scope_space_id UUID REFERENCES spaces(id),
                  scope_doc_type TEXT,
                  status TEXT NOT NULL DEFAULT 'draft',
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_workflow_steps (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  workflow_id UUID NOT NULL REFERENCES approval_workflows(id) ON DELETE CASCADE,
                  step_order INTEGER NOT NULL,
                  approver_role_id UUID REFERENCES global_roles(id),
                  sla_hours INTEGER,
                  escalates_to_step_order INTEGER,
                  UNIQUE (workflow_id, step_order)
                )
                """);
        jdbc.execute("""
                CREATE TABLE approval_requests (
                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                  document_id UUID NOT NULL REFERENCES documents(id),
                  workflow_id UUID NOT NULL REFERENCES approval_workflows(id),
                  temporal_workflow_id TEXT NOT NULL UNIQUE,
                  requested_by UUID NOT NULL REFERENCES users(id),
                  current_step_order INTEGER NOT NULL DEFAULT 1,
                  status TEXT NOT NULL DEFAULT 'en_cours',
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);

        jdbc.update("INSERT INTO spaces (id, name) VALUES (?,?), (?,?)",
                SPACE_A, "A", SPACE_B, "B");
        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?,?,?,?)",
                USER, "u@example.com", "U", "active");
        jdbc.update("INSERT INTO global_roles (id, name, description) VALUES (?,?,?), (?,?,?)",
                ROLE_EDITOR, "Éditeur de documents", "editeur",
                ROLE_ANALYST, "Analyste conformité", "analyste");
        jdbc.update("INSERT INTO documents (id, space_id, title, status, doc_type) VALUES (?,?,?,?,?)",
                DOC, SPACE_A, "Doc", "brouillon", "politique");
    }

    @BeforeEach
    void cleanWorkflows() {
        jdbc.update("DELETE FROM approval_requests");
        jdbc.update("DELETE FROM approval_workflow_steps");
        jdbc.update("DELETE FROM approval_workflows");
    }

    @Test
    void create_rejectsNonContiguousStepOrders() {
        assertThatThrownBy(() -> service.create(upsert("W", null, null, "active", List.of(
                step(1, 24, ROLE_EDITOR, 2),
                step(3, 24, ROLE_ANALYST, null)
        ))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ResponseStatusException) ex).getReason()).contains("contigus");
                });
    }

    @Test
    void create_rejectsSlaHoursNonPositive() {
        assertThatThrownBy(() -> service.create(upsert("W", null, null, "active", List.of(
                step(1, 0, ROLE_EDITOR, null)
        ))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).contains("sla_hours"));
    }

    @Test
    void create_rejectsUnknownApproverRole() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(upsert("W", null, null, "active", List.of(
                step(1, 24, unknown, null)
        ))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).contains("approver_role_id"));
    }

    @Test
    void create_rejectsInvalidEscalationTarget() {
        assertThatThrownBy(() -> service.create(upsert("W", null, null, "active", List.of(
                step(1, 24, ROLE_EDITOR, 99)
        ))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason())
                        .contains("escalates_to_step_order"));
    }

    @Test
    void create_threeSteps_persistsAndLoads() {
        DefinitionView created = service.create(upsert("Politique 3 étapes", SPACE_A, "politique", "active", List.of(
                step(1, 24, ROLE_EDITOR, 2),
                step(2, 48, ROLE_ANALYST, 3),
                step(3, null, ROLE_ANALYST, null)
        )));
        assertThat(created.steps()).hasSize(3);
        assertThat(created.steps().get(2).slaHours()).isNull();
        assertThat(service.get(created.id()).scopeDocType()).isEqualTo("politique");
    }

    @Test
    void update_blockedWhenInProgress() {
        DefinitionView created = service.create(upsert("Locked", null, null, "active", List.of(
                step(1, 24, ROLE_EDITOR, null)
        )));
        jdbc.update("""
                INSERT INTO approval_requests
                  (id, document_id, workflow_id, temporal_workflow_id, requested_by, status)
                VALUES (?, ?, ?, ?, ?, 'en_cours')
                """,
                UUID.randomUUID(), DOC, created.id(), "wf-lock", USER);

        assertThatThrownBy(() -> service.update(created.id(), upsert("Locked2", null, null, "active", List.of(
                step(1, 12, ROLE_EDITOR, null)
        ))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void resolve_prefersSpaceAndTypeOverPartials() {
        UUID global = service.create(upsert("Global", null, null, "active", List.of(
                step(1, 24, ROLE_EDITOR, null)))).id();
        UUID spaceOnly = service.create(upsert("Space A", SPACE_A, null, "active", List.of(
                step(1, 24, ROLE_EDITOR, null)))).id();
        UUID typeOnly = service.create(upsert("Type pol", null, "politique", "active", List.of(
                step(1, 24, ROLE_EDITOR, null)))).id();
        UUID exact = service.create(upsert("Exact", SPACE_A, "politique", "active", List.of(
                step(1, 24, ROLE_EDITOR, null),
                step(2, 24, ROLE_ANALYST, null)))).id();

        ApplicableView hit = service.resolveApplicable(SPACE_A, "politique");
        assertThat(hit.id()).isEqualTo(exact);
        assertThat(hit.matchLevel()).isEqualTo("space_type");
        assertThat(hit.stepCount()).isEqualTo(2);

        ApplicableView spaceHit = service.resolveApplicable(SPACE_A, "autre");
        assertThat(spaceHit.id()).isEqualTo(spaceOnly);
        assertThat(spaceHit.matchLevel()).isEqualTo("space");

        ApplicableView typeHit = service.resolveApplicable(SPACE_B, "politique");
        assertThat(typeHit.id()).isEqualTo(typeOnly);
        assertThat(typeHit.matchLevel()).isEqualTo("type");

        ApplicableView globalHit = service.resolveApplicable(SPACE_B, "autre");
        assertThat(globalHit.id()).isEqualTo(global);
        assertThat(globalHit.matchLevel()).isEqualTo("global");
    }

    @Test
    void resolve_noMatch_fallsBackToApprobationSimple() {
        ApplicableView hit = service.resolveApplicable(SPACE_A, "inexistant");
        assertThat(hit.name()).isEqualTo(ApprovalWorkflowDefinitionService.DEFAULT_WORKFLOW_NAME);
        assertThat(hit.matchLevel()).isEqualTo("fallback");
        assertThat(hit.stepCount()).isEqualTo(1);
    }

    @Test
    void create_rejectsDuplicateActiveScope() {
        service.create(upsert("Exact A", SPACE_A, "politique", "active", List.of(
                step(1, 24, ROLE_EDITOR, null))));
        assertThatThrownBy(() -> service.create(upsert("Exact B", SPACE_A, "Politique", "active", List.of(
                step(1, 12, ROLE_ANALYST, null)))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ResponseStatusException) ex).getReason()).contains("scope");
                });
        // brouillon OK sur le même scope
        DefinitionView draft = service.create(upsert("Exact draft", SPACE_A, "politique", "draft", List.of(
                step(1, 24, ROLE_EDITOR, null))));
        assertThat(draft.status()).isEqualTo("draft");
    }

    @Test
    void resolve_nullDocType_skipsTypeScoped_noException() {
        service.create(upsert("Type only", null, "politique", "active", List.of(
                step(1, 24, ROLE_EDITOR, null))));
        UUID global = service.create(upsert("Global null-type", null, null, "active", List.of(
                step(1, 24, ROLE_ANALYST, null)))).id();

        ApplicableView hit = service.resolveApplicable(SPACE_A, null);
        assertThat(hit.id()).isEqualTo(global);
        assertThat(hit.matchLevel()).isEqualTo("global");
    }

    @Test
    void resolve_legacyDuplicateSameSpecificity_picksEarliestCreated() {
        // Defense-in-depth : doublons injectés hors API (données legacy / course)
        // restent départagés de façon déterministe (created_at ASC, name ASC).
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO approval_workflows (id, name, scope_space_id, scope_doc_type, status, created_at)
                VALUES (?, ?, ?, NULL, 'active', now() - interval '1 minute')
                """, first, "Legacy first", SPACE_A);
        jdbc.update("""
                INSERT INTO approval_workflow_steps (id, workflow_id, step_order, sla_hours, approver_role_id)
                VALUES (?, ?, 1, 24, ?)
                """, UUID.randomUUID(), first, ROLE_EDITOR);
        jdbc.update("""
                INSERT INTO approval_workflows (id, name, scope_space_id, scope_doc_type, status, created_at)
                VALUES (?, ?, ?, NULL, 'active', now())
                """, second, "Legacy second", SPACE_A);
        jdbc.update("""
                INSERT INTO approval_workflow_steps (id, workflow_id, step_order, sla_hours, approver_role_id)
                VALUES (?, ?, 1, 12, ?)
                """, UUID.randomUUID(), second, ROLE_ANALYST);

        ApplicableView hit = service.resolveApplicable(SPACE_A, null);
        assertThat(hit.id()).isEqualTo(first);
        assertThat(hit.matchLevel()).isEqualTo("space");
    }

    @Test
    void matchLevel_unit() {
        RawWorkflow exact = new RawWorkflow(UUID.randomUUID(), "e", SPACE_A, "politique", null);
        assertThat(ApprovalWorkflowDefinitionService.matchLevel(exact, SPACE_A, "politique"))
                .isEqualTo("space_type");
        assertThat(ApprovalWorkflowDefinitionService.matchLevel(exact, SPACE_B, "politique")).isNull();

        RawWorkflow global = new RawWorkflow(UUID.randomUUID(), "g", null, null, null);
        assertThat(ApprovalWorkflowDefinitionService.matchLevel(global, SPACE_A, "x")).isEqualTo("global");
    }

    private static UpsertRequest upsert(
            String name, UUID spaceId, String docType, String status, List<StepInput> steps
    ) {
        return new UpsertRequest(name, spaceId, docType, status, steps);
    }

    private static StepInput step(int order, Integer sla, UUID role, Integer escalateTo) {
        return new StepInput(order, sla, role, escalateTo);
    }
}
