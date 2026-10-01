// SPDX-License-Identifier: AGPL-3.0-or-later
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessGroupValidationTest {

    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID UNKNOWN_GROUP = UUID.fromString("99999999-9999-9999-9999-999999999999");

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
    void grant_toUnknownGroup_rejectedBeforeFga() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "groupe introuvable"))
                .when(groupService).requireExists(UNKNOWN_GROUP);

        assertThatThrownBy(() -> controller.grant(
                adminJwt(),
                "space",
                SPACE,
                new AccessController.PermissionRequest("editor", "group", UNKNOWN_GROUP)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ResponseStatusException) ex).getReason()).contains("groupe");
                });

        verify(authorizationService, never()).grantPermission(any(), any(), any(), any(), any());
        verify(auditService, never()).recordSync(any(), any(Boolean.class), any(), any(), any(), any(), any());
    }

    private static UserEntity user(UUID id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail("a@example.com");
        u.setDisplayName("A");
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
