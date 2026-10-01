// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.audit;

import eu.socle.authz.AccessController;
import eu.socle.authz.AuthorizationService;
import eu.socle.document.ApprovalActivitiesImpl;
import eu.socle.document.DocumentEntity;
import eu.socle.document.DocumentRepository;
import eu.socle.document.DocumentService;
import eu.socle.document.DocumentVersionRepository;
import eu.socle.document.DocumentDtos.CreateDocumentRequest;
import eu.socle.document.DocumentDtos.UpdateDocumentRequest;
import eu.socle.team.GroupService;
import eu.socle.identity.IdentityFacade;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditEventWiringTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock AuditService auditService;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock GroupService groupService;
    @Mock IdentityFacade identityFacade;

    @Test
    void grantAccess_recordsAccessGranted() {
        AccessController controller = new AccessController(authorizationService, userSyncService, auditService, groupService, identityFacade);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        doNothing().when(authorizationService).grantPermission(any(), any(), any(), any(), any());
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(USER.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        controller.grant(jwt, "space", SPACE, new AccessController.PermissionRequest(
                "editor", "user", USER));

        verify(auditService).recordSync(
                eq(USER), eq(false), eq(AuditActions.ACCESS_GRANT_REQUESTED),
                eq("space"), eq(SPACE), anyMap(), isNull());
        verify(auditService).recordSync(
                eq(USER), eq(false), eq(AuditActions.ACCESS_GRANTED),
                eq("space"), eq(SPACE), anyMap(), isNull());
    }

    @Test
    void createAndUpdateDocument_recordsAuditEventsInOrder() {
        DocumentService service = new DocumentService(
                documentRepository, versionRepository, userSyncService, authorizationService,
                auditService, mock(eu.socle.document.ReliabilityScoreService.class),
                mock(eu.socle.trash.TrashService.class));
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        doNothing().when(authorizationService).requireSpaceRelation(any(), any(), any());
        doNothing().when(authorizationService).linkDocumentToSpace(any(), any(), any());
        doNothing().when(authorizationService).grantDocumentEditor(any(), any());
        when(authorizationService.provisionDocumentAccess(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        DocumentEntity saved = new DocumentEntity();
        saved.setId(DOC);
        saved.setSpaceId(SPACE);
        saved.setTitle("Titre");
        saved.setBody(Map.of("type", "doc"));
        saved.setStatus("brouillon");
        saved.setCurrentVersionNo(1);
        when(documentRepository.save(any())).thenAnswer(inv -> {
            DocumentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(DOC);
            }
            return e;
        });
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Jwt jwt = jwt(USER);
        service.create(jwt, new CreateDocumentRequest("Titre", Map.of("type", "doc"), null, SPACE));
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_CREATED),
                eq("document"), eq(DOC), anyMap(), isNull());

        when(documentRepository.findActiveById(DOC)).thenReturn(Optional.of(saved));
        when(documentRepository.findActiveByIdForUpdate(DOC)).thenReturn(Optional.of(saved));
        doNothing().when(authorizationService).requireDocumentRelation(any(), any(), any());
        service.update(jwt, DOC, new UpdateDocumentRequest("Titre 2", Map.of("type", "doc")));
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_VERSION_CREATED),
                eq("document"), eq(DOC), anyMap(), isNull());
        verify(auditService).record(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_UPDATED),
                eq("document"), eq(DOC), anyMap(), isNull());
    }

    @Test
    void submissionApproveAndEscalation_writeAuditViaActivities() {
        when(jdbcTemplate.queryForList(contains("current_version_no"), eq(DOC)))
                .thenReturn(List.of(Map.of(
                        "current_version_no", 1,
                        "body", Map.of("type", "doc"),
                        "git_head_sha", "abc",
                        "updated_by", USER,
                        "created_by", USER)));
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), any(UUID.class)))
                .thenReturn(List.of(Map.of("status", "en_cours", "current_step_order", 1)));
        when(jdbcTemplate.queryForObject(anyString(), eq(UUID.class), any())).thenReturn(DOC);

        var documentStore = mock(eu.socle.storage.DocumentStore.class);
        when(documentStore.writeCurrentContent(any(), any(), any(), any(), any(), any())).thenReturn("def");
        eu.socle.document.ApprovalRoleResolver resolver = mock(eu.socle.document.ApprovalRoleResolver.class);
        UUID eligibleApprover = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(resolver.resolveInScopeAssignees(any(), any())).thenReturn(List.of(eligibleApprover));
        org.mockito.Mockito.lenient().when(jdbcTemplate.queryForList(
                        contains("approver_role_id"),
                        any(Integer.class),
                        any(UUID.class)))
                .thenReturn(List.of(Map.of(
                        "role_id", UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                        "document_id", DOC,
                        "requested_by", USER)));
        org.mockito.Mockito.lenient().when(jdbcTemplate.query(
                        contains("submitted_version_no"),
                        any(org.springframework.jdbc.core.ResultSetExtractor.class),
                        any()))
                .thenReturn(null);
        org.mockito.Mockito.lenient().when(jdbcTemplate.query(
                        contains("document_versions dv"),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        any(), any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(jdbcTemplate.query(
                        contains("COALESCE(updated_by"),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        any()))
                .thenReturn(List.of(USER));
        ApprovalActivitiesImpl activities = new ApprovalActivitiesImpl(
                jdbcTemplate, auditService,
                mock(eu.socle.document.ReliabilityScoreService.class),
                documentStore,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                resolver);
        UUID requestId = UUID.randomUUID();

        activities.recordSubmission(DOC, USER, requestId, UUID.randomUUID(), "wf-1", 1, 24);
        verify(documentStore).archiveVersion(eq(DOC), eq(1), any(), eq(USER), eq(USER), anyString());
        verify(auditService).recordSync(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_SUBMITTED),
                eq("document"), eq(DOC), anyMap(), isNull());

        activities.recordFinalDecision(DOC, requestId, 1, "approuve", USER, "ok");
        verify(auditService).recordSync(
                eq(USER), eq(false), eq(AuditActions.DOCUMENT_APPROVED),
                eq("document"), eq(DOC), anyMap(), isNull());

        activities.recordSlaEscalation(requestId, 1, 2, 24, ApprovalActivitiesImpl.SYSTEM_ACTOR_ID);
        ArgumentCaptor<UUID> actor = ArgumentCaptor.forClass(UUID.class);
        verify(auditService).recordSync(
                actor.capture(), eq(true), eq(AuditActions.APPROVAL_ESCALATED),
                eq("document"), eq(DOC), anyMap(), isNull());
        assertThat(actor.getValue()).isNull();
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        u.setStatus("active");
        return u;
    }

    private static Jwt jwt(UUID sub) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(sub.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
