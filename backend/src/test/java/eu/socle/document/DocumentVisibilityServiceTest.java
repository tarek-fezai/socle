// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.authz.AccessController;
import eu.socle.authz.AuthorizationService;
import eu.socle.identity.IdentityFacade;
import eu.socle.team.GroupService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Visibilité organisation | space | restricted — créateur = editor, owners gèrent.
 *
 * <p>Adaptation : les tests qui supposaient créateur = owner sont remplacés —
 * grant/revoke et PUT visibility exigent owner (héritage espace).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentVisibilityServiceTest {

    static final UUID CREATOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID OUTSIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock DocumentRepository documentRepository;
    @Mock DocumentVersionRepository versionRepository;
    @Mock UserSyncService userSyncService;
    @Mock AuthorizationService authorizationService;
    @Mock AuditService auditService;
    @Mock ReliabilityScoreService reliabilityScoreService;
    @Mock JdbcTemplate jdbc;
    @Mock GroupService groupService;
    @Mock IdentityFacade identityFacade;

    DocumentService service;
    AccessController accessController;

    @BeforeEach
    void setUp() {
        service = new DocumentService(
                documentRepository,
                userSyncService,
                authorizationService,
                auditService,
                reliabilityScoreService,
                org.mockito.Mockito.mock(eu.socle.trash.TrashService.class),
                new eu.socle.storage.RelationalDocumentStore(versionRepository),
                null,
                null,
                jdbc,
                null);
        accessController = new AccessController(
                authorizationService, userSyncService, auditService, groupService, identityFacade);
        when(documentRepository.save(any())).thenAnswer(inv -> {
            DocumentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(DOC);
            }
            return e;
        });
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_usesSpaceDefaultOrganisation_grantsEditorNotOwner() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(CREATOR));
        doNothing().when(authorizationService).requireSpaceRelation(CREATOR, SPACE, "editor");
        when(jdbc.query(any(String.class), any(ResultSetExtractor.class), any(Object.class)))
                .thenReturn(DocumentVisibility.ORGANISATION);
        when(authorizationService.provisionDocumentAccess(any(), any(), any(), any()))
                .thenReturn(List.of());

        var res = service.create(
                jwt(CREATOR),
                new DocumentDtos.CreateDocumentRequest("Page", Map.of("type", "doc"), null, SPACE));

        assertThat(res.visibility()).isEqualTo(DocumentVisibility.ORGANISATION);
        verify(authorizationService).provisionDocumentAccess(
                DOC, SPACE, CREATOR, DocumentVisibility.ORGANISATION);
        verify(authorizationService, never()).grantDocumentOwner(any(), any());
    }

    @Test
    void create_nonOwnerCannotOverrideVisibility() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(CREATOR));
        doNothing().when(authorizationService).requireSpaceRelation(CREATOR, SPACE, "editor");
        when(jdbc.query(any(String.class), any(ResultSetExtractor.class), any(Object.class)))
                .thenReturn(DocumentVisibility.ORGANISATION);
        when(authorizationService.hasRelation(CREATOR, "space", SPACE, "owner")).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                jwt(CREATOR),
                new DocumentDtos.CreateDocumentRequest(
                        "Page", Map.of("type", "doc"), null, SPACE, DocumentVisibility.RESTRICTED)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void creator_notOwner_gets403OnGrantAndVisibility() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(CREATOR));
        when(authorizationService.hasRelation(CREATOR, "document", DOC, "owner")).thenReturn(false);

        assertThatThrownBy(() -> accessController.grant(
                jwt(CREATOR),
                "document",
                DOC,
                new AccessController.PermissionRequest("viewer", "user", OUTSIDER)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> accessController.revoke(
                jwt(CREATOR),
                "document",
                DOC,
                new AccessController.PermissionRequest("viewer", "user", OUTSIDER)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        DocumentEntity entity = document(DOC, DocumentVisibility.ORGANISATION);
        when(documentRepository.findActiveByIdForUpdate(DOC)).thenReturn(Optional.of(entity));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (owner)"))
                .when(authorizationService).requireDocumentRelation(CREATOR, DOC, "owner");

        assertThatThrownBy(() -> service.updateVisibility(jwt(CREATOR), DOC, DocumentVisibility.RESTRICTED))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(authorizationService, never()).applyVisibilityTuples(any(), any(), any(), any());
    }

    @Test
    void owner_canChangeVisibility_auditsFromTo() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(OWNER));
        doNothing().when(authorizationService).requireDocumentRelation(OWNER, DOC, "owner");
        DocumentEntity entity = document(DOC, DocumentVisibility.ORGANISATION);
        when(documentRepository.findActiveByIdForUpdate(DOC)).thenReturn(Optional.of(entity));
        doNothing().when(authorizationService).applyVisibilityTuples(any(), any(), any(), any());

        var res = service.updateVisibility(jwt(OWNER), DOC, DocumentVisibility.RESTRICTED);

        assertThat(res.visibility()).isEqualTo(DocumentVisibility.RESTRICTED);
        verify(authorizationService).applyVisibilityTuples(
                DOC, "space:" + SPACE, DocumentVisibility.ORGANISATION, DocumentVisibility.RESTRICTED);
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(OWNER), eq(false), eq(AuditActions.DOCUMENT_VISIBILITY_CHANGED),
                eq("document"), eq(DOC), meta.capture(), isNull());
        assertThat(meta.getValue()).containsEntry("from", "organisation").containsEntry("to", "restricted");
    }

    @Test
    void visibilityChange_fgaFailure_doesNotLeaveAppliedTuplesCallWithoutException() {
        when(userSyncService.syncFromJwt(any())).thenReturn(user(OWNER));
        doNothing().when(authorizationService).requireDocumentRelation(OWNER, DOC, "owner");
        DocumentEntity entity = document(DOC, DocumentVisibility.SPACE);
        when(documentRepository.findActiveByIdForUpdate(DOC)).thenReturn(Optional.of(entity));
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenFGA write failed"))
                .when(authorizationService)
                .applyVisibilityTuples(any(), any(), any(), any());

        assertThatThrownBy(() -> service.updateVisibility(jwt(OWNER), DOC, DocumentVisibility.ORGANISATION))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(auditService, never()).record(
                any(), any(Boolean.class), eq(AuditActions.DOCUMENT_VISIBILITY_CHANGED),
                any(), any(), anyMap(), any());
    }

    private static DocumentEntity document(UUID id, String visibility) {
        DocumentEntity e = new DocumentEntity();
        e.setId(id);
        e.setSpaceId(SPACE);
        e.setTitle("Titre");
        e.setBody(new HashMap<>(Map.of("type", "doc")));
        e.setStatus("brouillon");
        e.setCurrentVersionNo(1);
        e.setVisibility(visibility);
        return e;
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(id + "@test.local");
        u.setDisplayName("User");
        u.setStatus("active");
        return u;
    }

    private static Jwt jwt(UUID userId) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("email", userId + "@test.local")
                .build();
    }
}
