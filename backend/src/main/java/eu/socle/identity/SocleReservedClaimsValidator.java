// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Les claims {@code socle_*} sont réservés aux jetons synthétiques PAT : un JWT IdP qui
 * en porte un est rejeté (401 {@code invalid_token}).
 */
public final class SocleReservedClaimsValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token",
            "Claim réservé Socle (socle_*) interdit dans un JWT IdP",
            null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt == null) {
            return OAuth2TokenValidatorResult.success();
        }
        for (String name : jwt.getClaims().keySet()) {
            if (name != null && name.startsWith("socle_")) {
                return OAuth2TokenValidatorResult.failure(ERROR);
            }
        }
        return OAuth2TokenValidatorResult.success();
    }
}
