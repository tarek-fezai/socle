// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;

/**
 * Authentification issue d'un jeton d'accès personnel (Bearer {@code pat_…}).
 * Distincte d'un {@link JwtAuthenticationToken} IdP : les claims {@code socle_*} d'un JWT
 * signé par l'IdP ne peuvent pas produire cette classe.
 */
public final class PatAuthenticationToken extends JwtAuthenticationToken {

    public PatAuthenticationToken(
            Jwt jwt,
            Collection<? extends GrantedAuthority> authorities,
            String name
    ) {
        super(jwt, authorities, name);
    }
}
