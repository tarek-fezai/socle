package eu.socle.document;

import eu.socle.authz.AuthorizationService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalsMineTest {

    static final UUID APPROVER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OUTSIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID REQUESTER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID REQ = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID ROLE = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID OTHER_DOC = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    static final UUID OTHER_REQ = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Mock WorkflowClient workflowClient;
    @Mock DocumentRepository documentRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock ApprovalActivitiesImpl activities;
    @Mock ApprovalWorkflowDefinitionService workflowDefinitions;
    @Mock ApprovalRoleResolver approvalRoleResolver;

    DocumentApprovalService service;

    @BeforeEach
    void setUp() {
        service = new DocumentApprovalService(
                workflowClient,
                documentRepository,
                userSyncService,
                authorizationService,
                jdbcTemplate,
                activities,
                workflowDefinitions,
                approvalRoleResolver,
                passthroughTx(),
                false
        );
    }

    @Test
    void listMine_returnsOnlyRequestsMatchingApproverRole() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(APPROVER, "approver@example.com"));
        when(jdbcTemplate.query(contains("approval_requests ar"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(REQ);
                    when(rs.getObject("document_id")).thenReturn(DOC);
                    when(rs.getString("document_title")).thenReturn("Politique accès");
                    when(rs.getString("temporal_workflow_id")).thenReturn("wf-1");
                    when(rs.getString("status")).thenReturn("en_cours");
                    when(rs.getInt("current_step_order")).thenReturn(1);
                    when(rs.getTimestamp("sla_deadline_at"))
                            .thenReturn(Timestamp.from(Instant.parse("2026-09-29T12:00:00Z")));
                    when(rs.getObject("submitted_version_no")).thenReturn(2);
                    when(rs.getObject("baseline_version_no")).thenReturn(1);
                    when(rs.getObject("requested_by")).thenReturn(REQUESTER);
                    when(rs.getTimestamp("created_at"))
                            .thenReturn(Timestamp.from(Instant.parse("2026-09-28T10:00:00Z")));
                    when(rs.getObject("approver_role_id")).thenReturn(ROLE);
                    when(rs.getObject("submitted_author_id")).thenReturn(REQUESTER);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(approvalRoleResolver.canDecide(APPROVER, ROLE, DOC)).thenReturn(true);
        when(authorizationService.hasRelation(APPROVER, "document", DOC, "editor")).thenReturn(true);

        List<DocumentApprovalService.ApprovalView> mine = service.listMine(jwt(APPROVER));

        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().approvalRequestId()).isEqualTo(REQ);
        assertThat(mine.getFirst().documentTitle()).isEqualTo("Politique accès");
        assertThat(mine.getFirst().slaDeadlineAt()).isEqualTo("2026-09-29T12:00:00Z");
        assertThat(mine.getFirst().submittedVersionNo()).isEqualTo(2);
        assertThat(mine.getFirst().baselineVersionNo()).isEqualTo(1);
        verify(approvalRoleResolver).canDecide(APPROVER, ROLE, DOC);
    }

    @Test
    void listMine_excludesOwnSubmission_fourEyes() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(APPROVER, "approver@example.com"));
        when(jdbcTemplate.query(contains("approval_requests ar"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rsOwn = org.mockito.Mockito.mock(ResultSet.class);
                    when(rsOwn.getObject("id")).thenReturn(REQ);
                    when(rsOwn.getObject("document_id")).thenReturn(DOC);
                    when(rsOwn.getString("document_title")).thenReturn("Ma soumission");
                    when(rsOwn.getString("temporal_workflow_id")).thenReturn("wf-own");
                    when(rsOwn.getString("status")).thenReturn("en_cours");
                    when(rsOwn.getInt("current_step_order")).thenReturn(1);
                    when(rsOwn.getTimestamp("sla_deadline_at")).thenReturn(null);
                    when(rsOwn.getObject("submitted_version_no")).thenReturn(1);
                    when(rsOwn.getObject("baseline_version_no")).thenReturn(null);
                    when(rsOwn.getObject("requested_by")).thenReturn(APPROVER);
                    when(rsOwn.getTimestamp("created_at")).thenReturn(null);
                    when(rsOwn.getObject("approver_role_id")).thenReturn(ROLE);
                    when(rsOwn.getObject("submitted_author_id")).thenReturn(APPROVER);

                    ResultSet rsOther = org.mockito.Mockito.mock(ResultSet.class);
                    when(rsOther.getObject("id")).thenReturn(OTHER_REQ);
                    when(rsOther.getObject("document_id")).thenReturn(OTHER_DOC);
                    when(rsOther.getString("document_title")).thenReturn("Autre soumission");
                    when(rsOther.getString("temporal_workflow_id")).thenReturn("wf-other");
                    when(rsOther.getString("status")).thenReturn("en_cours");
                    when(rsOther.getInt("current_step_order")).thenReturn(1);
                    when(rsOther.getTimestamp("sla_deadline_at")).thenReturn(null);
                    when(rsOther.getObject("submitted_version_no")).thenReturn(1);
                    when(rsOther.getObject("baseline_version_no")).thenReturn(null);
                    when(rsOther.getObject("requested_by")).thenReturn(REQUESTER);
                    when(rsOther.getTimestamp("created_at")).thenReturn(null);
                    when(rsOther.getObject("approver_role_id")).thenReturn(ROLE);
                    when(rsOther.getObject("submitted_author_id")).thenReturn(REQUESTER);

                    return List.of(mapper.mapRow(rsOwn, 0), mapper.mapRow(rsOther, 1));
                });
        when(approvalRoleResolver.canDecide(APPROVER, ROLE, DOC)).thenReturn(true);
        when(approvalRoleResolver.canDecide(APPROVER, ROLE, OTHER_DOC)).thenReturn(true);
        when(authorizationService.hasRelation(APPROVER, "document", DOC, "editor")).thenReturn(true);
        when(authorizationService.hasRelation(APPROVER, "document", OTHER_DOC, "editor")).thenReturn(true);

        List<DocumentApprovalService.ApprovalView> mine = service.listMine(jwt(APPROVER));

        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().approvalRequestId()).isEqualTo(OTHER_REQ);
    }

    @Test
    void decide_forbiddenWhenActorIsRequester_fourEyes() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(APPROVER, "approver@example.com"));
        when(authorizationService.hasRelation(APPROVER, "document", DOC, "editor")).thenReturn(true);
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(REQ)))
                .thenReturn(List.of(Map.of(
                        "temporal_workflow_id", "wf-1",
                        "status", "en_cours",
                        "document_id", DOC,
                        "current_step_order", 1,
                        "requested_by", APPROVER,
                        "submitted_version_no", 1
                )));

        assertThatThrownBy(() -> service.decide(
                jwt(APPROVER),
                DOC,
                REQ,
                new DocumentApprovalService.DecisionRequest("approuve", null, 1)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("séparation des tâches");
                });
    }

    @Test
    void listMine_emptyWhenUserHasNoMatchingRole() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(OUTSIDER, "outsider@example.com"));
        when(jdbcTemplate.query(contains("approval_requests ar"), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getObject("id")).thenReturn(REQ);
                    when(rs.getObject("document_id")).thenReturn(DOC);
                    when(rs.getString("document_title")).thenReturn("x");
                    when(rs.getString("temporal_workflow_id")).thenReturn("wf-1");
                    when(rs.getString("status")).thenReturn("en_cours");
                    when(rs.getInt("current_step_order")).thenReturn(1);
                    when(rs.getTimestamp("sla_deadline_at")).thenReturn(null);
                    when(rs.getObject("submitted_version_no")).thenReturn(null);
                    when(rs.getObject("baseline_version_no")).thenReturn(null);
                    when(rs.getObject("requested_by")).thenReturn(REQUESTER);
                    when(rs.getTimestamp("created_at")).thenReturn(null);
                    when(rs.getObject("approver_role_id")).thenReturn(ROLE);
                    when(rs.getObject("submitted_author_id")).thenReturn(REQUESTER);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(approvalRoleResolver.canDecide(OUTSIDER, ROLE, DOC)).thenReturn(false);

        assertThat(service.listMine(jwt(OUTSIDER))).isEmpty();
    }

    @Test
    void decide_forbiddenWhenNotCurrentStepApprover() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(OUTSIDER, "outsider@example.com"));
        when(authorizationService.hasRelation(OUTSIDER, "document", DOC, "editor")).thenReturn(true);
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(REQ)))
                .thenReturn(List.of(Map.of(
                        "temporal_workflow_id", "wf-1",
                        "status", "en_cours",
                        "document_id", DOC,
                        "current_step_order", 1,
                        "requested_by", REQUESTER
                )));
        when(approvalRoleResolver.canDecideCurrentStep(OUTSIDER, REQ)).thenReturn(false);

        assertThatThrownBy(() -> service.decide(
                jwt(OUTSIDER),
                DOC,
                REQ,
                new DocumentApprovalService.DecisionRequest("approuve", null, 1)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).isEqualTo("rôle hors portée");
                });
    }

    @Test
    void decide_forbiddenWhenNoDocumentAccess() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(OUTSIDER, "outsider@example.com"));
        when(authorizationService.hasRelation(OUTSIDER, "document", DOC, "editor")).thenReturn(false);

        assertThatThrownBy(() -> service.decide(
                jwt(OUTSIDER),
                DOC,
                REQ,
                new DocumentApprovalService.DecisionRequest("approuve", null, 1)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).isEqualTo("pas d'accès à la page");
                });
    }

    @Test
    void decide_conflictWhenAlreadyResolved() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(APPROVER, "approver@example.com"));
        when(authorizationService.hasRelation(APPROVER, "document", DOC, "editor")).thenReturn(true);
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(REQ)))
                .thenReturn(List.of(Map.of(
                        "temporal_workflow_id", "wf-1",
                        "status", "approuve",
                        "document_id", DOC,
                        "current_step_order", 1
                )));

        assertThatThrownBy(() -> service.decide(
                jwt(APPROVER),
                DOC,
                REQ,
                new DocumentApprovalService.DecisionRequest("approuve", null, 1)
        ))
                .isInstanceOf(ApprovalConflictException.class)
                .satisfies(ex -> {
                    ApprovalConflictException ace = (ApprovalConflictException) ex;
                    assertThat(ace.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ace.getError()).isEqualTo(ApprovalConflictException.ALREADY_RESOLVED);
                });
    }

    static TransactionTemplate passthroughTx() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            }
        });
    }

    private static UserEntity user(UUID id, String email) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(email);
        u.setDisplayName(email);
        u.setStatus("active");
        u.setSystemAccount(false);
        return u;
    }

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("email", sub + "@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
