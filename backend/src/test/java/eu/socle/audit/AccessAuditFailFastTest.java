package eu.socle.audit;

import eu.socle.authz.AccessController;
import eu.socle.authz.AuthorizationService;
import eu.socle.identity.IdentityFacade;
import eu.socle.team.GroupService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Échec audit « requested » (avant FGA) → 503 sans toucher OpenFGA.
 */
@ExtendWith(MockitoExtension.class)
class AccessAuditFailFastTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock AuditService auditService;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock GroupService groupService;
    @Mock IdentityFacade identityFacade;

    @Test
    void grant_failsWhenRequestedAuditInsertFails_withoutTouchingFga() {
        AccessController controller = new AccessController(authorizationService, userSyncService, auditService, groupService, identityFacade);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        doThrow(new AuditService.AuditWriteException("db down", new RuntimeException("constraint")))
                .when(auditService).recordSync(
                        any(), anyBoolean(), eq(AuditActions.ACCESS_GRANT_REQUESTED),
                        anyString(), any(), anyMap(), isNull());

        assertThatThrownBy(() -> controller.grant(
                adminJwt(), "space", SPACE,
                new AccessController.PermissionRequest("editor", "user", USER)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(503));

        verify(authorizationService, never()).grantPermission(any(), any(), any(), any(), any());
        verify(auditService).recordSync(
                eq(USER), eq(false), eq(AuditActions.ACCESS_GRANT_REQUESTED),
                eq("space"), eq(SPACE), anyMap(), isNull());
    }

    @Test
    void revoke_failsWhenRequestedAuditInsertFails_withoutTouchingFga() {
        AccessController controller = new AccessController(authorizationService, userSyncService, auditService, groupService, identityFacade);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(USER));
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
        doThrow(new AuditService.AuditWriteException("db down", new RuntimeException("constraint")))
                .when(auditService).recordSync(
                        any(), anyBoolean(), eq(AuditActions.ACCESS_REVOKE_REQUESTED),
                        anyString(), any(), anyMap(), isNull());

        assertThatThrownBy(() -> controller.revoke(
                adminJwt(), "folder", SPACE,
                new AccessController.PermissionRequest("viewer", "user", USER)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(503));

        verify(authorizationService, never()).revokePermission(any(), any(), any(), any(), any());
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("u@example.com");
        u.setDisplayName("U");
        u.setStatus("active");
        return u;
    }

    private static Jwt adminJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(USER.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
