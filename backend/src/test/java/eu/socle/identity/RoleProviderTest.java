// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import eu.socle.audit.AuditService;
import eu.socle.user.UserEntity;
import eu.socle.user.UserRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleProviderTest {

    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock PlatformRoleRepository platformRoleRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;

    IdentityProperties properties;
    IdentityClaimsMapper claimsMapper;
    PlatformRoleService platformRoleService;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        claimsMapper = new IdentityClaimsMapper(properties);
        platformRoleService = new PlatformRoleService(platformRoleRepository, userRepository, auditService);
        when(userRepository.existsById(USER)).thenReturn(true);
    }

    @Test
    void internal_ignoresClaims() {
        properties.setRoleSource(IdentityProperties.RoleSource.INTERNAL);
        PlatformRoleEntity row = roleRow(SocleRole.AUDITEUR);
        when(platformRoleRepository.findByUserId(USER)).thenReturn(List.of(row));

        RoleProvider provider = new InternalRoleProvider(platformRoleRepository, properties);
        Jwt jwt = jwtWithRealmRoles("administrateur-systeme");

        assertThat(provider.resolve(jwt, USER))
                .containsExactlyInAnyOrder(SocleRole.AUDITEUR, SocleRole.CONTRIBUTEUR);
    }

    @Test
    void both_unionsClaimsAndInternal() {
        properties.setRoleSource(IdentityProperties.RoleSource.BOTH);
        when(platformRoleRepository.findByUserId(USER))
                .thenReturn(List.of(roleRow(SocleRole.INTEGRATEUR)));

        RoleProvider provider = new CompositeRoleProvider(
                new ClaimsRoleProvider(claimsMapper),
                new InternalRoleProvider(platformRoleRepository, properties));

        Jwt jwt = jwtWithRealmRoles("auditeur");
        assertThat(provider.resolve(jwt, USER))
                .containsExactlyInAnyOrder(
                        SocleRole.AUDITEUR,
                        SocleRole.INTEGRATEUR,
                        SocleRole.CONTRIBUTEUR);
    }

    @Test
    void bootstrap_grantsAdminWhenConfigured() {
        when(platformRoleRepository.findByUserIdAndRole(USER, SocleRole.ADMINISTRATEUR_SYSTEME.name()))
                .thenReturn(Optional.empty());
        when(platformRoleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        platformRoleService.grantBootstrapAdminIfNeeded(USER, "bootstrap-sub");

        verify(platformRoleRepository).save(any(PlatformRoleEntity.class));
        verify(auditService).record(eq(USER), eq(false), any(), eq("user"), eq(USER), any(), eq(null));
    }

    @Test
    void cannotRemoveLastAdmin() {
        when(platformRoleRepository.findByUserIdAndRole(USER, SocleRole.ADMINISTRATEUR_SYSTEME.name()))
                .thenReturn(Optional.of(roleRow(SocleRole.ADMINISTRATEUR_SYSTEME)));
        when(platformRoleRepository.countByRole(SocleRole.ADMINISTRATEUR_SYSTEME.name())).thenReturn(1L);

        assertThatThrownBy(() -> platformRoleService.revoke(USER, USER, SocleRole.ADMINISTRATEUR_SYSTEME))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(platformRoleRepository, never()).deleteByUserIdAndRole(any(), any());
    }

    private static PlatformRoleEntity roleRow(SocleRole role) {
        PlatformRoleEntity e = new PlatformRoleEntity();
        e.setUserId(USER);
        e.setRole(role.name());
        e.setGrantedAt(Instant.now());
        return e;
    }

    private static Jwt jwtWithRealmRoles(String... roles) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(USER.toString())
                .issuer("http://localhost:8081/realms/socle")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
