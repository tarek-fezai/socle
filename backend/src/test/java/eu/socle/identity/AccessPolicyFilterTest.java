// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import eu.socle.user.UserEntity;
import eu.socle.user.UserIdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessPolicyFilterTest {

    static final String ISSUER = "http://localhost:8081/realms/socle";
    static final String SUBJECT = "sub-1";

    @Mock UserIdentityService identityService;
    @Mock AccessAuditService accessAuditService;

    IdentityProperties properties;
    MutableClock clock;
    AccessDecisionCache cache;
    AccessPolicyFilter filter;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        IdentityClaimsMapper mapper = new IdentityClaimsMapper(properties);
        clock = new MutableClock();
        cache = new AccessDecisionCache(clock);
        filter = new AccessPolicyFilter(
                new AccessPolicyService(properties, mapper, identityService),
                cache, accessAuditService, mapper);
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void granted_requestContinuesDownTheChain() throws Exception {
        Result r = call("/api/v1/me", jwt("a@corp.com", List.of()));

        assertThat(r.chainInvoked()).isTrue();
        assertThat(r.response().getStatus()).isEqualTo(200);
        verify(accessAuditService, never()).recordDenied(any(), any(), any(), any());
    }

    @Test
    void denied_returns403JsonWithReason_andAuditsDenial() throws Exception {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.REQUIRE_GROUP);
        properties.getAccessPolicy().setAllowedGroups(List.of("socle-users"));

        Result r = call("/api/v1/me", jwt("a@corp.com", List.of("other")));

        assertThat(r.chainInvoked()).isFalse();
        assertThat(r.response().getStatus()).isEqualTo(403);
        assertThat(r.response().getContentType()).startsWith("application/json");
        assertThat(r.response().getContentAsString())
                .isEqualTo("{\"error\":\"access_denied\",\"reason\":\"not_in_allowed_group\"}");
        verify(accessAuditService).recordDenied(
                eq(ISSUER), eq(SUBJECT), isNull(), eq(AccessDeniedReason.NOT_IN_ALLOWED_GROUP));
    }

    @Test
    void denied_bodyNeverLeaksPolicyConfiguration() throws Exception {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.REQUIRE_GROUP);
        properties.getAccessPolicy().setAllowedGroups(List.of("secret-group"));
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("secret-domain.example"));

        Result r = call("/api/v1/documents", jwt("a@secret-domain.example", List.of()));

        assertThat(r.response().getStatus()).isEqualTo(403);
        assertThat(r.response().getContentAsString())
                .doesNotContain("secret-group")
                .doesNotContain("secret-domain");
    }

    @Test
    void allReasons_mapTo403Body() throws Exception {
        // not_provisioned
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.PROVISIONED_ONLY);
        assertThat(call("/api/v1/me", jwt("a@corp.com", List.of())).response().getContentAsString())
                .contains("\"reason\":\"not_provisioned\"");

        // email_domain_not_allowed
        cache.invalidateAll();
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.JIT);
        properties.getAccessPolicy().setAllowedEmailDomains(List.of("corp.com"));
        assertThat(call("/api/v1/me", jwt("a@evil.com", List.of())).response().getContentAsString())
                .contains("\"reason\":\"email_domain_not_allowed\"");

        // account_disabled
        cache.invalidateAll();
        properties.getAccessPolicy().setAllowedEmailDomains(List.of());
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(user("disabled")));
        assertThat(call("/api/v1/me", jwt("a@corp.com", List.of())).response().getContentAsString())
                .contains("\"reason\":\"account_disabled\"");
    }

    @Test
    void decisionIsCached_forUpTo60Seconds() throws Exception {
        UserEntity active = user("active");
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(active));

        call("/api/v1/me", jwt("a@corp.com", List.of()));
        call("/api/v1/me", jwt("a@corp.com", List.of()));
        clock.advance(Duration.ofSeconds(59));
        call("/api/v1/me", jwt("a@corp.com", List.of()));

        verify(identityService, times(1)).findUserByIdentity(ISSUER, SUBJECT);
    }

    @Test
    void disabledAccount_refusedAfterCacheExpiry_evenWithValidJwt() throws Exception {
        UserEntity user = user("active");
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(user));
        Jwt jwt = jwt("a@corp.com", List.of());

        assertThat(call("/api/v1/me", jwt).response().getStatus()).isEqualTo(200);

        // désactivation « hors bande » (sans invalidation explicite du cache)
        user.setStatus("disabled");
        clock.advance(Duration.ofSeconds(30));
        assertThat(call("/api/v1/me", jwt).response().getStatus())
                .as("décision encore en cache")
                .isEqualTo(200);

        clock.advance(Duration.ofSeconds(31));
        Result after = call("/api/v1/me", jwt);
        assertThat(after.response().getStatus()).isEqualTo(403);
        assertThat(after.response().getContentAsString()).contains("\"reason\":\"account_disabled\"");
    }

    @Test
    void explicitInvalidation_appliesImmediately() throws Exception {
        UserEntity user = user("active");
        when(identityService.findUserByIdentity(ISSUER, SUBJECT)).thenReturn(Optional.of(user));
        Jwt jwt = jwt("a@corp.com", List.of());
        assertThat(call("/api/v1/me", jwt).response().getStatus()).isEqualTo(200);

        user.setStatus("disabled");
        cache.invalidate(ISSUER, SUBJECT);

        assertThat(call("/api/v1/me", jwt).response().getStatus()).isEqualTo(403);
    }

    @Test
    void publicEndpointsAndNonApiPaths_areNotFiltered() throws Exception {
        properties.getAccessPolicy().setMode(IdentityProperties.AccessMode.PROVISIONED_ONLY);
        Jwt jwt = jwt("a@corp.com", List.of());

        assertThat(call("/api/v1/public/auth-config", jwt).chainInvoked()).isTrue();
        assertThat(call("/actuator/health", jwt).chainInvoked()).isTrue();
    }

    @Test
    void unauthenticatedRequest_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setRequestURI("/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // --- helpers -----------------------------------------------------------------------------

    private record Result(MockHttpServletResponse response, boolean chainInvoked) {}

    private Result call(String uri, Jwt jwt) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain.getRequest() != null);
    }

    private static UserEntity user(String status) {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("a@corp.com");
        u.setDisplayName("A");
        u.setStatus(status);
        return u;
    }

    private static Jwt jwt(String email, List<String> groups) {
        return Jwt.withTokenValue("super-secret-token")
                .header("alg", "none")
                .issuer(ISSUER)
                .claim("sub", SUBJECT)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("groups", groups)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
