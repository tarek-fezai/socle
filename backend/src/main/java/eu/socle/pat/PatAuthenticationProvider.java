// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import eu.socle.identity.IdentityProperties;
import eu.socle.identity.PatClaims;
import eu.socle.identity.RoleProvider;
import eu.socle.identity.SocleRole;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * Bearer {@code pat_…} : le principal agit comme l'utilisateur — {@link Jwt} synthétique
 * (iss/sub de son identité liée), rôles relus en base à chaque requête ; OpenFGA reste
 * évalué par les services sur ce même principal. Tout refus = 401 générique.
 */
@Component
public class PatAuthenticationProvider implements AuthenticationProvider {

    static final String INVALID = "Invalid token";

    private final PatService patService;
    private final RoleProvider roleProvider;
    private final IdentityProperties identityProperties;
    private final Clock clock;

    public PatAuthenticationProvider(
            PatService patService,
            RoleProvider roleProvider,
            IdentityProperties identityProperties,
            Clock clock
    ) {
        this.patService = patService;
        this.roleProvider = roleProvider;
        this.identityProperties = identityProperties;
        this.clock = clock;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String presented = ((BearerTokenAuthenticationToken) authentication).getToken();
        PatService.Authenticated pat = patService.authenticate(presented)
                .orElseThrow(() -> new InvalidBearerTokenException(INVALID));

        Jwt jwt = syntheticJwt(pat);
        Set<SocleRole> roles = roleProvider.resolve(jwt, pat.userId());
        List<GrantedAuthority> authorities = roles.stream()
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority(r.authority()))
                .toList();
        PatAuthenticationToken token = new PatAuthenticationToken(jwt, authorities, pat.subject());
        token.setDetails(authentication.getDetails());
        return token;
    }

    private Jwt syntheticJwt(PatService.Authenticated pat) {
        Jwt.Builder builder = Jwt.withTokenValue(PatClaims.TOKEN_VALUE_PREFIX + pat.patId())
                .header("alg", "none")
                .claim("iss", pat.issuer())
                .claim(identityProperties.getSubjectClaim(), pat.subject())
                .claim(PatClaims.AUTH_METHOD, PatClaims.AUTH_METHOD_PAT)
                .claim(PatClaims.PAT_ID, pat.patId().toString())
                .claim(PatClaims.PAT_USER_ID, pat.userId().toString())
                .claim(PatClaims.PAT_SCOPE, pat.scope().value())
                .issuedAt(clock.instant())
                .expiresAt(pat.expiresAt());
        if (!"sub".equals(identityProperties.getSubjectClaim())) {
            builder.subject(pat.subject());
        }
        if (pat.email() != null) {
            builder.claim(identityProperties.getEmailClaim(), pat.email());
        }
        if (pat.displayName() != null) {
            builder.claim(identityProperties.getNameClaim(), pat.displayName());
        }
        return builder.build();
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return BearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
