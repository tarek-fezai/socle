// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Décodeur JWT IdP + validateur des claims réservés {@code socle_*}.
 * Les Bearer {@code pat_…} n'atteignent jamais ce décodeur
 * ({@code AuthenticationManagerResolver}).
 */
public final class SocleGuardedJwtDecoder implements JwtDecoder {

    private final JwtDecoder delegate;
    private final OAuth2TokenValidator<Jwt> reservedClaims;

    public SocleGuardedJwtDecoder(JwtDecoder delegate, OAuth2TokenValidator<Jwt> reservedClaims) {
        this.delegate = delegate;
        this.reservedClaims = reservedClaims;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        Jwt jwt = delegate.decode(token);
        OAuth2TokenValidatorResult result = reservedClaims.validate(jwt);
        if (result.hasErrors()) {
            Collection<OAuth2Error> errors = new ArrayList<>();
            result.getErrors().forEach(errors::add);
            throw new JwtValidationException(
                    "JWT IdP rejeté : claim réservé Socle",
                    errors);
        }
        return jwt;
    }
}
