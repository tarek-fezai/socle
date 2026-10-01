// SPDX-License-Identifier: AGPL-3.0-or-later
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSyncServiceTest {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final String ISSUER_ENTRA = "https://login.microsoftonline.com/tenant/v2.0";
    static final String SUBJECT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final UUID USER_ID = UUID.fromString(SUBJECT);

    @Mock UserRepository userRepository;
    @Mock UserIdentityRepository identityRepository;
    @Mock UserIdentityService identityService;
    @Mock PlatformRoleService platformRoleService;

    IdentityProperties properties;
    IdentityClaimsMapper claimsMapper;
    UserSyncService syncService;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        claimsMapper = new IdentityClaimsMapper(properties);
        syncService = new UserSyncService(
                userRepository, identityService, identityRepository,
                claimsMapper, properties, platformRoleService);
    }

    @Test
    void emailChange_keepsSameAccountByIssuerAndSubject() {
        UserEntity existing = user(USER_ID, "old@example.com");
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(existing));
        when(identityRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
                .thenReturn(Optional.of(identity(USER_ID, ISSUER, SUBJECT)));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity synced = syncService.syncFromJwt(jwt(ISSUER, SUBJECT, "new@example.com", "New Name", null));

        assertThat(synced.getId()).isEqualTo(USER_ID);
        assertThat(synced.getEmail()).isEqualTo("new@example.com");
        verify(identityService, never()).ensurePrimaryIdentity(any(), any(), any());
        verify(platformRoleService, never()).grantBootstrapAdminIfNeeded(any(), any());
    }

    @Test
    void firstLogin_reusesSubjectUuidWhenFree() {
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        when(identityRepository.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        when(userRepository.existsById(USER_ID)).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity synced = syncService.syncFromJwt(jwt(ISSUER, SUBJECT, "a@example.com", "A", null));
        assertThat(synced.getId()).isEqualTo(USER_ID);
        verify(identityService).ensurePrimaryIdentity(USER_ID, ISSUER, SUBJECT);
    }

    @Test
    void sameEmail_differentIssuerSubject_createsDistinctAccounts() {
        when(identityService.findUserByIdentity(eq(ISSUER), any())).thenReturn(Optional.empty());
        when(identityService.findUserByIdentity(eq(ISSUER_ENTRA), any())).thenReturn(Optional.empty());
        when(identityRepository.findByIssuerAndSubject(any(), any())).thenReturn(Optional.empty());
        when(userRepository.existsById(any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });

        String subA = UUID.randomUUID().toString();
        String subB = UUID.randomUUID().toString();
        UserEntity a = syncService.syncFromJwt(jwt(ISSUER, subA, "same@corp.com", "A", null));
        UserEntity b = syncService.syncFromJwt(jwt(ISSUER_ENTRA, subB, "same@corp.com", "B", null));

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getEmail()).isEqualTo("same@corp.com");
        assertThat(b.getEmail()).isEqualTo("same@corp.com");
    }

    @Test
    void linkByVerifiedEmail_false_doesNotAttach() {
        properties.setLinkByVerifiedEmail(false);
        when(identityService.findUserByIdentity(ISSUER_ENTRA, "oid-1")).thenReturn(Optional.empty());
        when(identityRepository.findByIssuerAndSubject(ISSUER_ENTRA, "oid-1")).thenReturn(Optional.empty());
        when(userRepository.existsById(any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        syncService.syncFromJwt(jwt(ISSUER_ENTRA, "oid-1", "x@corp.com", "X", true));

        verify(userRepository, never()).findAllByEmailIgnoreCase(any());
        verify(identityService).ensurePrimaryIdentity(any(), eq(ISSUER_ENTRA), eq("oid-1"));
    }

    @Test
    void linkByVerifiedEmail_withoutVerifiedClaim_noAttach() {
        properties.setLinkByVerifiedEmail(true);
        when(identityService.findUserByIdentity(ISSUER_ENTRA, "oid-1")).thenReturn(Optional.empty());
        when(identityRepository.findByIssuerAndSubject(ISSUER_ENTRA, "oid-1")).thenReturn(Optional.empty());
        when(userRepository.existsById(any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        syncService.syncFromJwt(jwt(ISSUER_ENTRA, "oid-1", "x@corp.com", "X", false));

        verify(userRepository, never()).findAllByEmailIgnoreCase(any());
    }

    @Test
    void linkByVerifiedEmail_twoAccountsSameEmail_noAttach() {
        properties.setLinkByVerifiedEmail(true);
        when(identityService.findUserByIdentity(ISSUER_ENTRA, "oid-1")).thenReturn(Optional.empty());
        when(identityRepository.findByIssuerAndSubject(ISSUER_ENTRA, "oid-1")).thenReturn(Optional.empty());
        when(userRepository.findAllByEmailIgnoreCase("x@corp.com"))
                .thenReturn(List.of(user(UUID.randomUUID(), "x@corp.com"), user(UUID.randomUUID(), "x@corp.com")));
        when(userRepository.existsById(any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity created = syncService.syncFromJwt(jwt(ISSUER_ENTRA, "oid-1", "x@corp.com", "X", true));

        assertThat(created.getId()).isNotNull();
        ArgumentCaptor<UUID> idCap = ArgumentCaptor.forClass(UUID.class);
        verify(identityService).ensurePrimaryIdentity(idCap.capture(), eq(ISSUER_ENTRA), eq("oid-1"));
        // nouveau compte, pas rattachement aux existants
        assertThat(idCap.getValue()).isEqualTo(created.getId());
    }

    @Test
    void linkByVerifiedEmail_singleAccount_attaches() {
        properties.setLinkByVerifiedEmail(true);
        UUID existingId = UUID.randomUUID();
        UserEntity existing = user(existingId, "x@corp.com");
        when(identityService.findUserByIdentity(ISSUER_ENTRA, "oid-1")).thenReturn(Optional.empty());
        when(userRepository.findAllByEmailIgnoreCase("x@corp.com")).thenReturn(List.of(existing));
        when(identityRepository.existsByUserIdAndIssuer(existingId, ISSUER_ENTRA)).thenReturn(false);
        when(identityRepository.findByIssuerAndSubject(ISSUER_ENTRA, "oid-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(identity(existingId, ISSUER_ENTRA, "oid-1")));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserEntity synced = syncService.syncFromJwt(jwt(ISSUER_ENTRA, "oid-1", "x@corp.com", "X", true));

        assertThat(synced.getId()).isEqualTo(existingId);
        verify(identityService).ensurePrimaryIdentity(existingId, ISSUER_ENTRA, "oid-1");
    }

    private static UserEntity user(UUID id, String email) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(email);
        u.setDisplayName(email);
        u.setStatus("active");
        return u;
    }

    private static UserIdentityEntity identity(UUID userId, String issuer, String subject) {
        UserIdentityEntity e = new UserIdentityEntity();
        e.setUserId(userId);
        e.setIssuer(issuer);
        e.setSubject(subject);
        e.setLinkedAt(Instant.now());
        return e;
    }

    private static Jwt jwt(String issuer, String subject, String email, String name, Boolean verified) {
        var b = Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer(issuer)
                .claim("sub", subject)
                .claim("email", email)
                .claim("name", name)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        if (verified != null) {
            b.claim("email_verified", verified);
        }
        return b.build();
    }
}
