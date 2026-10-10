// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

import eu.socle.audit.AuditActions;
import eu.socle.audit.AuditService;
import eu.socle.identity.AccessDecisionCache;
import eu.socle.identity.AccessPolicyService;
import eu.socle.identity.PlatformRoleEntity;
import eu.socle.identity.PlatformRoleRepository;
import eu.socle.identity.SocleRole;
import eu.socle.pat.PatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAccountServiceTest {

    static final String ADMIN = SocleRole.ADMINISTRATEUR_SYSTEME.name();
    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID TARGET = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock UserRepository userRepository;
    @Mock UserIdentityRepository identityRepository;
    @Mock PlatformRoleRepository platformRoleRepository;
    @Mock AuditService auditService;
    @Mock PatService patService;

    AccessDecisionCache cache;
    UserAccountService service;

    @BeforeEach
    void setUp() {
        cache = new AccessDecisionCache();
        service = new UserAccountService(
                userRepository, identityRepository, platformRoleRepository, auditService, cache, patService);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(identityRepository.findByUserId(TARGET)).thenReturn(List.of(identity()));
    }

    @Test
    void disable_setsStatus_audits_andInvalidatesAccessCache() {
        UserEntity user = user("active");
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user));
        cache.put(ISSUER, "sub-target", AccessPolicyService.Decision.GRANTED);

        UserEntity result = service.disable(ACTOR, TARGET);

        assertThat(result.getStatus()).isEqualTo("disabled");
        assertThat(cache.get(ISSUER, "sub-target")).isEmpty();
        verify(auditService).record(
                eq(ACTOR), eq(false), eq(AuditActions.USER_DISABLED),
                eq("user"), eq(TARGET), anyMap(), isNull());
        verify(patService).revokeAllForUser(TARGET, ACTOR, PatService.REASON_USER_DISABLED);
    }

    @Test
    void enable_setsStatusActive_audits_andInvalidatesAccessCache() {
        UserEntity user = user("disabled");
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user));
        cache.put(ISSUER, "sub-target",
                AccessPolicyService.Decision.denied(eu.socle.identity.AccessDeniedReason.ACCOUNT_DISABLED));

        UserEntity result = service.enable(ACTOR, TARGET);

        assertThat(result.getStatus()).isEqualTo("active");
        assertThat(cache.get(ISSUER, "sub-target")).isEmpty();
        verify(auditService).record(
                eq(ACTOR), eq(false), eq(AuditActions.USER_ENABLED),
                eq("user"), eq(TARGET), anyMap(), isNull());
    }

    @Test
    void disable_lastSystemAdmin_isRefused() {
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user("active")));
        when(platformRoleRepository.findByUserIdAndRole(TARGET, ADMIN)).thenReturn(Optional.of(adminRole()));
        when(platformRoleRepository.countByRole(ADMIN)).thenReturn(1L);
        when(platformRoleRepository.countEnabledByRole(ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.disable(ACTOR, TARGET))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(userRepository, never()).save(any());
        verify(auditService, never()).record(any(), eq(false), eq(AuditActions.USER_DISABLED),
                any(), any(), anyMap(), any());
    }

    @Test
    void disable_lastEnabledAdmin_isRefused_evenIfAnotherAdminIsAlreadyDisabled() {
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user("active")));
        when(platformRoleRepository.findByUserIdAndRole(TARGET, ADMIN)).thenReturn(Optional.of(adminRole()));
        when(platformRoleRepository.countByRole(ADMIN)).thenReturn(2L);
        when(platformRoleRepository.countEnabledByRole(ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.disable(ACTOR, TARGET))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void disable_adminWhenOthersRemain_isAllowed() {
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user("active")));
        when(platformRoleRepository.findByUserIdAndRole(TARGET, ADMIN)).thenReturn(Optional.of(adminRole()));
        when(platformRoleRepository.countByRole(ADMIN)).thenReturn(2L);
        when(platformRoleRepository.countEnabledByRole(ADMIN)).thenReturn(2L);

        assertThat(service.disable(ACTOR, TARGET).getStatus()).isEqualTo("disabled");
    }

    @Test
    void disable_unknownUser_is404() {
        when(userRepository.findById(TARGET)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disable(ACTOR, TARGET))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void disable_alreadyDisabled_isIdempotent_noSecondAudit() {
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user("disabled")));

        service.disable(ACTOR, TARGET);

        verify(auditService, never()).record(any(), eq(false), any(), any(), any(), anyMap(), any());
    }

    private static UserEntity user(String status) {
        UserEntity u = new UserEntity();
        u.setId(TARGET);
        u.setEmail("t@corp.com");
        u.setDisplayName("T");
        u.setStatus(status);
        return u;
    }

    private static UserIdentityEntity identity() {
        UserIdentityEntity e = new UserIdentityEntity();
        e.setUserId(TARGET);
        e.setIssuer(ISSUER);
        e.setSubject("sub-target");
        e.setLinkedAt(Instant.now());
        return e;
    }

    private static PlatformRoleEntity adminRole() {
        PlatformRoleEntity r = new PlatformRoleEntity();
        r.setUserId(TARGET);
        r.setRole(ADMIN);
        r.setGrantedAt(Instant.now());
        return r;
    }
}
