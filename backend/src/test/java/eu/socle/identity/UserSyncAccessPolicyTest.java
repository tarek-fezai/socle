// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import eu.socle.user.UserEntity;
import eu.socle.user.UserIdentityEntity;
import eu.socle.user.UserIdentityRepository;
import eu.socle.user.UserIdentityService;
import eu.socle.user.UserRepository;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Politique d'accès dans {@link UserSyncService} : refus = aucun compte créé. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSyncAccessPolicyTest {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final String SUBJECT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final UUID USER_ID = UUID.fromString(SUBJECT);

    @Mock UserRepository userRepository;
    @Mock UserIdentityRepository identityRepository;
    @Mock UserIdentityService identityService;
    @Mock PlatformRoleService platformRoleService;
    @Mock AccessAuditService accessAuditService;

    IdentityProperties properties;
    UserSyncService syncService;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        IdentityClaimsMapper mapper = new IdentityClaimsMapper(properties);
        syncService = new UserSyncService(
                userRepository, identityService, identityRepository, mapper, properties,
                platformRoleService, new AccessPolicyService(properties, mapper, identityService),
                accessAuditService, null);
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        when(identityRepository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        when(userRepository.existsById(any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- jit ------------------------------------------------------------------------------

    @Test
    void jit_createsUserAndAuditsAccessGrantedOnce() {
        UserEntity created = syncService.syncFromJwt(jwt("a@corp.com", true, List.of()));

        assertThat(created.getId()).isEqualTo(USER_ID);
        verify(identityService).ensurePrimaryIdentity(USER_ID, ISSUER, SUBJECT);
        verify(accessAuditService).recordGranted(USER_ID, ISSUER, SUBJECT, IdentityProperties.AccessMode.JIT);

        // 2ᵉ connexion : compte connu, lastLoginAt renseigné → pas de nouvel audit
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(created));
        when(identityRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
                .thenReturn(Optional.of(identity(USER_ID)));
        syncService.syncFromJwt(jwt("a@corp.com", true, List.of()));
        verify(accessAuditService, times(1)).recordGranted(any(), any(), any(), any());
    }

    @Test
    void jit_emailDomainNotAllowed_refusesWithoutCreatingUser() {
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("corp.com"));

        assertDenied(jwt("a@evil.com", true, List.of()), AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);
        assertDenied(jwt("a@corp.com", false, List.of()), AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);
        assertNothingCreated();
    }

    // --- require-group ------------------------------------------------------------------------

    @Test
    void requireGroup_granted_whenInAllowedGroup() {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.REQUIRE_GROUP);
        properties.getAccessPolicy().setAllowedGroups(List.of("socle-users"));

        UserEntity created = syncService.syncFromJwt(jwt("a@corp.com", true, List.of("socle-users")));

        assertThat(created.getId()).isEqualTo(USER_ID);
        verify(accessAuditService).recordGranted(
                USER_ID, ISSUER, SUBJECT, IdentityProperties.AccessMode.REQUIRE_GROUP);
    }

    @Test
    void requireGroup_refused_whenNotInGroup_noUserCreated() {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.REQUIRE_GROUP);
        properties.getAccessPolicy().setAllowedGroups(List.of("socle-users"));

        assertDenied(jwt("a@corp.com", true, List.of("other")), AccessDeniedReason.NOT_IN_ALLOWED_GROUP);
        assertNothingCreated();
    }

    // --- provisioned-only ----------------------------------------------------------------------

    @Test
    void provisionedOnly_refused_whenNoIdentity_noUserCreated() {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.PROVISIONED_ONLY);

        assertDenied(jwt("a@corp.com", true, List.of()), AccessDeniedReason.NOT_PROVISIONED);
        assertNothingCreated();
    }

    @Test
    void provisionedOnly_granted_whenIdentityExists_andFirstLoginAudited() {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.PROVISIONED_ONLY);
        UserEntity provisioned = user("active"); // jamais connecté : lastLoginAt == null
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(provisioned));
        when(identityRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
                .thenReturn(Optional.of(identity(USER_ID)));

        syncService.syncFromJwt(jwt("a@corp.com", true, List.of()));
        verify(accessAuditService).recordGranted(
                USER_ID, ISSUER, SUBJECT, IdentityProperties.AccessMode.PROVISIONED_ONLY);

        syncService.syncFromJwt(jwt("a@corp.com", true, List.of()));
        verify(accessAuditService, times(1)).recordGranted(any(), any(), any(), any());
    }

    // --- disabled ----------------------------------------------------------------------------------

    @Test
    void disabledExistingUser_refused_inJitMode_andNotSaved() {
        UserEntity disabled = user("disabled");
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(disabled));

        assertDenied(jwt("a@corp.com", true, List.of()), AccessDeniedReason.ACCOUNT_DISABLED);
        verify(userRepository, never()).save(any());
        verify(accessAuditService, never()).recordGranted(any(), any(), any(), any());
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private void assertDenied(Jwt jwt, AccessDeniedReason reason) {
        assertThatThrownBy(() -> syncService.syncFromJwt(jwt))
                .isInstanceOfSatisfying(AccessPolicyDeniedException.class,
                        e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    private void assertNothingCreated() {
        verify(userRepository, never()).save(any());
        verify(identityService, never()).ensurePrimaryIdentity(any(), any(), any());
        verify(accessAuditService, never()).recordGranted(any(), any(), any(), any());
    }

    private static UserEntity user(String status) {
        UserEntity u = new UserEntity();
        u.setId(USER_ID);
        u.setEmail("a@corp.com");
        u.setDisplayName("A");
        u.setStatus(status);
        return u;
    }

    private static UserIdentityEntity identity(UUID userId) {
        UserIdentityEntity e = new UserIdentityEntity();
        e.setUserId(userId);
        e.setIssuer(ISSUER);
        e.setSubject(SUBJECT);
        e.setLinkedAt(Instant.now());
        return e;
    }

    private static Jwt jwt(String email, boolean verified, List<String> groups) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer(ISSUER)
                .claim("sub", SUBJECT)
                .claim("email", email)
                .claim("email_verified", verified)
                .claim("groups", groups)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
