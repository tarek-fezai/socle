// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.authz;

import eu.socle.audit.AuditService;
import eu.socle.identity.IdentityFacade;
import eu.socle.team.GroupService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessControllerManageForbiddenTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock AuthorizationService authorizationService;
    @Mock UserSyncService userSyncService;
    @Mock AuditService auditService;
    @Mock GroupService groupService;
    @Mock IdentityFacade identityFacade;
    @Mock AccessDirectoryEnricher enricher;

    AccessController controller;

    @BeforeEach
    void setUp() {
        controller = new AccessController(
                authorizationService, userSyncService, auditService, groupService, identityFacade, enricher);
        UserEntity u = new UserEntity();
        u.setId(USER);
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        when(identityFacade.isSystemAdmin(any())).thenReturn(false);
        when(authorizationService.hasRelation(eq(USER), eq("space"), eq(SPACE), eq("owner"))).thenReturn(false);
    }

    @Test
    void list_withoutManage_403() {
        assertThatThrownBy(() -> controller.list(jwt(), "space", SPACE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(authorizationService, never()).listAccessEntries(any(), any());
    }

    @Test
    void grant_withoutManage_403() {
        assertThatThrownBy(() -> controller.grant(
                jwt(), "space", SPACE, new AccessController.PermissionRequest("editor", "user", USER)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(authorizationService, never()).grantPermission(any(), any(), any(), any(), any());
    }

    @Test
    void revoke_withoutManage_403() {
        assertThatThrownBy(() -> controller.revoke(
                jwt(), "space", SPACE, new AccessController.PermissionRequest("editor", "user", USER)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
                        ((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(authorizationService, never()).revokePermission(any(), any(), any(), any(), any());
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
