// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.authz;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.team.GroupService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cohérence grant/revoke : 503 ⇒ pas de changement FGA persistant non tracé (option A).
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessFgaAuditConsistencyTest {

    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SUBJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock AuditService auditService;
    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock GroupService groupService;
    @Mock IdentityFacade identityFacade;

    AccessController controller;

    @BeforeEach
    void setUp() {
        controller = new AccessController(authorizationService, userSyncService, auditService, groupService, identityFacade);
        when(userSyncService.syncFromJwt(any())).thenReturn(user(ACTOR));
        when(identityFacade.isSystemAdmin(any())).thenReturn(true);
    }

    @Test
    void grantSuccess_writesRequestedThenFgaThenApplied() {
        doNothing().when(authorizationService).grantPermission(any(), any(), any(), any(), any());

        controller.grant(adminJwt(), "space", SPACE, req());

        InOrder order = inOrder(auditService, authorizationService);
        order.verify(auditService).recordSync(
                eq(ACTOR), eq(false), eq(AuditActions.ACCESS_GRANT_REQUESTED),
                eq("space"), eq(SPACE), anyMap(), isNull());
        order.verify(authorizationService).grantPermission(
                eq("space"), eq(SPACE), eq("editor"), eq("user"), eq(SUBJECT));
        order.verify(auditService).recordSync(
                eq(ACTOR), eq(false), eq(AuditActions.ACCESS_GRANTED),
                eq("space"), eq(SPACE), anyMap(), isNull());
        verify(authorizationService, never()).revokePermission(any(), any(), any(), any(), any());
    }

    @Test
    void grant_requestedAuditFails_noFgaWrite() {
        doThrow(new AuditService.AuditWriteException("db", new RuntimeException("x")))
                .when(auditService).recordSync(
                        any(), anyBoolean(), eq(AuditActions.ACCESS_GRANT_REQUESTED),
                        anyString(), any(), anyMap(), isNull());

        assertThatThrownBy(() -> controller.grant(adminJwt(), "space", SPACE, req()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(503));

        verify(authorizationService, never()).grantPermission(any(), any(), any(), any(), any());
        verify(authorizationService, never()).revokePermission(any(), any(), any(), any(), any());
        verify(auditService, never()).recordSync(
                any(), anyBoolean(), eq(AuditActions.ACCESS_GRANTED),
                anyString(), any(), anyMap(), isNull());
    }

    @Test
    void grant_appliedAuditFails_compensatesWithRevoke() {
        doNothing().when(authorizationService).grantPermission(any(), any(), any(), any(), any());
        doNothing().when(authorizationService).revokePermission(any(), any(), any(), any(), any());
        // requested OK ; applied KO
        doThrow(new AuditService.AuditWriteException("db", new RuntimeException("x")))
                .when(auditService).recordSync(
                        any(), anyBoolean(), eq(AuditActions.ACCESS_GRANTED),
                        anyString(), any(), anyMap(), isNull());

        assertThatThrownBy(() -> controller.grant(adminJwt(), "space", SPACE, req()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(503));

        InOrder order = inOrder(authorizationService);
        order.verify(authorizationService).grantPermission(
                eq("space"), eq(SPACE), eq("editor"), eq("user"), eq(SUBJECT));
        order.verify(authorizationService).revokePermission(
                eq("space"), eq(SPACE), eq("editor"), eq("user"), eq(SUBJECT));
    }

    @Test
    void revokeSuccess_writesRequestedThenFgaThenApplied() {
        doNothing().when(authorizationService).revokePermission(any(), any(), any(), any(), any());

        controller.revoke(adminJwt(), "space", SPACE, req());

        InOrder order = inOrder(auditService, authorizationService);
        order.verify(auditService).recordSync(
                eq(ACTOR), eq(false), eq(AuditActions.ACCESS_REVOKE_REQUESTED),
                eq("space"), eq(SPACE), anyMap(), isNull());
        order.verify(authorizationService).revokePermission(
                eq("space"), eq(SPACE), eq("editor"), eq("user"), eq(SUBJECT));
        order.verify(auditService).recordSync(
                eq(ACTOR), eq(false), eq(AuditActions.ACCESS_REVOKED),
                eq("space"), eq(SPACE), anyMap(), isNull());
    }

    @Test
    void revoke_appliedAuditFails_compensatesWithReGrant() {
        doNothing().when(authorizationService).revokePermission(any(), any(), any(), any(), any());
        doNothing().when(authorizationService).grantPermission(any(), any(), any(), any(), any());
        doThrow(new AuditService.AuditWriteException("db", new RuntimeException("x")))
                .when(auditService).recordSync(
                        any(), anyBoolean(), eq(AuditActions.ACCESS_REVOKED),
                        anyString(), any(), anyMap(), isNull());

        assertThatThrownBy(() -> controller.revoke(adminJwt(), "space", SPACE, req()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(503));

        InOrder order = inOrder(authorizationService);
        order.verify(authorizationService).revokePermission(
                eq("space"), eq(SPACE), eq("editor"), eq("user"), eq(SUBJECT));
        order.verify(authorizationService).grantPermission(
                eq("space"), eq(SPACE), eq("editor"), eq("user"), eq(SUBJECT));
    }

    @Test
    void grant_doubleFailure_emitsAlertCritical(CapturedOutput output) {
        doNothing().when(authorizationService).grantPermission(any(), any(), any(), any(), any());
        doThrow(new RuntimeException("OpenFGA down"))
                .when(authorizationService).revokePermission(any(), any(), any(), any(), any());
        doThrow(new AuditService.AuditWriteException("db", new RuntimeException("x")))
                .when(auditService).recordSync(
                        any(), anyBoolean(), eq(AuditActions.ACCESS_GRANTED),
                        anyString(), any(), anyMap(), isNull());

        assertThatThrownBy(() -> controller.grant(adminJwt(), "space", SPACE, req()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(503));

        assertThat(output.getOut() + output.getErr()).contains(AccessController.ALERT_CRITICAL);
    }

    private static AccessController.PermissionRequest req() {
        return new AccessController.PermissionRequest("editor", "user", SUBJECT);
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
                .subject(ACTOR.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
