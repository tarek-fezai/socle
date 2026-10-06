// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import eu.socle.user.UserEntity;
import eu.socle.user.UserIdentityService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessPolicyServiceTest {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final String SUBJECT = "sub-1";

    @Mock UserIdentityService identityService;

    IdentityProperties properties;
    AccessPolicyService service;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        service = new AccessPolicyService(properties, new IdentityClaimsMapper(properties), identityService);
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.empty());
    }

    // --- jit -------------------------------------------------------------------------

    @Test
    void jit_isDefault_andGrantsUnknownUser() {
        assertThat(properties.getAccessPolicy().getMode()).isEqualTo(IdentityProperties.AccessMode.JIT);
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of())).granted()).isTrue();
    }

    @Test
    void jit_existingActiveUser_granted() {
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(user("active")));
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of())).granted()).isTrue();
    }

    // --- disabled --------------------------------------------------------------------

    @Test
    void disabledAccount_deniedInEveryMode() {
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(user("disabled")));
        for (IdentityProperties.AccessMode mode : IdentityProperties.AccessMode.values()) {
            properties.getAccessPolicy().setMode(mode);
            properties.getAccessPolicy().setAllowedGroups(List.of("socle-users"));
            assertThat(service.evaluate(jwt("a@corp.com", true, List.of("socle-users"))).reason())
                    .as("mode %s", mode)
                    .isEqualTo(AccessDeniedReason.ACCOUNT_DISABLED);
        }
    }

    // --- require-group -----------------------------------------------------------------

    @Test
    void requireGroup_memberGranted() {
        requireGroup("socle-users", "socle-admins");
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of("x", "socle-admins"))).granted()).isTrue();
    }

    @Test
    void requireGroup_nonMember_notInAllowedGroup() {
        requireGroup("socle-users");
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of("other"))).reason())
                .isEqualTo(AccessDeniedReason.NOT_IN_ALLOWED_GROUP);
    }

    @Test
    void requireGroup_noGroupsClaim_denied() {
        requireGroup("socle-users");
        assertThat(service.evaluate(jwt("a@corp.com", true, null)).reason())
                .isEqualTo(AccessDeniedReason.NOT_IN_ALLOWED_GROUP);
    }

    @Test
    void requireGroup_emptyAllowedGroups_failsClosed() {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.REQUIRE_GROUP);
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of("anything"))).reason())
                .isEqualTo(AccessDeniedReason.NOT_IN_ALLOWED_GROUP);
    }

    @Test
    void requireGroup_usesConfiguredGroupsClaim() {
        requireGroup("team-a");
        properties.getAccessPolicy().setGroupsClaim("roles");
        Jwt jwt = base("a@corp.com", true).claim("roles", List.of("team-a")).build();
        assertThat(service.evaluate(jwt).granted()).isTrue();
        // l'ancien claim « groups » n'est plus lu
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of("team-a"))).reason())
                .isEqualTo(AccessDeniedReason.NOT_IN_ALLOWED_GROUP);
    }

    // --- provisioned-only ----------------------------------------------------------------

    @Test
    void provisionedOnly_unknownIdentity_notProvisioned() {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.PROVISIONED_ONLY);
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of())).reason())
                .isEqualTo(AccessDeniedReason.NOT_PROVISIONED);
    }

    @Test
    void provisionedOnly_knownIdentity_granted() {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.PROVISIONED_ONLY);
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(user("active")));
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of())).granted()).isTrue();
    }

    // --- allowed-email-domains -------------------------------------------------------------

    @Test
    void emailDomains_matchAndVerified_granted() {
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("Corp.com", "@partner.eu"));
        assertThat(service.evaluate(jwt("a@CORP.com", true, List.of())).granted()).isTrue();
        assertThat(service.evaluate(jwt("b@partner.eu", true, List.of())).granted()).isTrue();
    }

    @Test
    void emailDomains_otherDomain_denied() {
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("corp.com"));
        assertThat(service.evaluate(jwt("a@evil.com", true, List.of())).reason())
                .isEqualTo(AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);
        assertThat(service.evaluate(jwt("a@sub.corp.com", true, List.of())).reason())
                .isEqualTo(AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);
    }

    @Test
    void emailDomains_unverifiedEmail_denied() {
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("corp.com"));
        assertThat(service.evaluate(jwt("a@corp.com", false, List.of())).reason())
                .isEqualTo(AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);
    }

    @Test
    void emailDomains_missingEmail_denied() {
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("corp.com"));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").issuer(ISSUER)
                .claim("sub", SUBJECT).claim("email_verified", true)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(service.evaluate(jwt).reason()).isEqualTo(AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);
    }

    @Test
    void emailDomains_checkedBeforeGroup() {
        requireGroup("socle-users");
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("corp.com"));
        assertThat(service.evaluate(jwt("a@evil.com", true, List.of("socle-users"))).reason())
                .isEqualTo(AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of("nope"))).reason())
                .isEqualTo(AccessDeniedReason.NOT_IN_ALLOWED_GROUP);
        assertThat(service.evaluate(jwt("a@corp.com", true, List.of("socle-users"))).granted()).isTrue();
    }

    @Test
    void emptyDomains_meansAllDomains_evenUnverified() {
        assertThat(service.evaluate(jwt("a@anything.io", false, List.of())).granted()).isTrue();
    }

    @Test
    void reasonCodes_areStable() {
        assertThat(AccessDeniedReason.NOT_IN_ALLOWED_GROUP.code()).isEqualTo("not_in_allowed_group");
        assertThat(AccessDeniedReason.ACCOUNT_DISABLED.code()).isEqualTo("account_disabled");
        assertThat(AccessDeniedReason.NOT_PROVISIONED.code()).isEqualTo("not_provisioned");
        assertThat(AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED.code()).isEqualTo("email_domain_not_allowed");
    }

    // --- helpers ---------------------------------------------------------------------------

    private void requireGroup(String... groups) {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.REQUIRE_GROUP);
        properties.getAccessPolicy().setAllowedGroups(List.of(groups));
    }

    private static UserEntity user(String status) {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("a@corp.com");
        u.setDisplayName("A");
        u.setStatus(status);
        return u;
    }

    private static Jwt.Builder base(String email, boolean verified) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer(ISSUER)
                .claim("sub", SUBJECT)
                .claim("email", email)
                .claim("email_verified", verified)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
    }

    private static Jwt jwt(String email, boolean verified, List<String> groups) {
        Jwt.Builder b = base(email, verified);
        if (groups != null) {
            b.claim("groups", groups);
        }
        return b.build();
    }
}
