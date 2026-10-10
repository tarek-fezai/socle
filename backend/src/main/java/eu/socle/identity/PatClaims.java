// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

/**
 * Marqueurs du {@link Jwt} synthétique porté par une authentification par jeton d'accès
 * personnel : le principal reste un {@code Jwt} pour que les contrôleurs existants
 * ({@code @AuthenticationPrincipal Jwt}) agissent comme l'utilisateur, sans jamais exposer le secret.
 *
 * <p>Un JWT IdP ne peut pas se faire passer pour un PAT : {@link #isPat} exige aussi que
 * {@code tokenValue} commence par {@code pat:} (valeur produite uniquement par
 * {@code PatAuthenticationProvider} ; un Bearer IdP compact ne commence jamais ainsi).
 * Les claims {@code socle_*} dans un JWT IdP sont de plus rejetés par
 * {@link SocleReservedClaimsValidator}.
 */
public final class PatClaims {

    public static final String AUTH_METHOD = "socle_auth_method";
    public static final String AUTH_METHOD_PAT = "pat";
    public static final String PAT_ID = "socle_pat_id";
    public static final String PAT_USER_ID = "socle_pat_user_id";
    public static final String PAT_SCOPE = "socle_pat_scope";

    /** Préfixe du {@code tokenValue} synthétique ({@code pat:<uuid>}). */
    public static final String TOKEN_VALUE_PREFIX = "pat:";

    private PatClaims() {}

    public static boolean isPat(Jwt jwt) {
        return jwt != null
                && AUTH_METHOD_PAT.equals(jwt.getClaimAsString(AUTH_METHOD))
                && jwt.getTokenValue() != null
                && jwt.getTokenValue().startsWith(TOKEN_VALUE_PREFIX);
    }

    public static Optional<UUID> patId(Jwt jwt) {
        return uuidClaim(jwt, PAT_ID);
    }

    public static Optional<UUID> userId(Jwt jwt) {
        return uuidClaim(jwt, PAT_USER_ID);
    }

    private static Optional<UUID> uuidClaim(Jwt jwt, String claim) {
        if (!isPat(jwt)) {
            return Optional.empty();
        }
        String raw = jwt.getClaimAsString(claim);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
