// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Jeton d'accès personnel : rôles relus en base à chaque requête ({@code user_platform_roles}
 * + rôle par défaut) — jamais figés à la création. En mode {@code CLAIMS} les rôles n'existent
 * que dans le JWT de l'IdP : un PAT ne porte que le rôle par défaut.
 *
 * <p>Fail-closed admin : {@link SocleRole#ADMINISTRATEUR_SYSTEME} est toujours retiré du
 * principal PAT (URL {@code /admin/**}, {@code /api-docs}, et tous les contrôles service via
 * {@link IdentityFacade#isSystemAdmin} / autorités Spring).
 */
public class PatAwareRoleProvider implements RoleProvider {

    private final RoleProvider jwtRoles;
    private final RoleProvider patRoles;

    public PatAwareRoleProvider(RoleProvider jwtRoles, RoleProvider patRoles) {
        this.jwtRoles = jwtRoles;
        this.patRoles = patRoles;
    }

    @Override
    public Set<SocleRole> resolve(Jwt jwt, UUID userId) {
        if (!PatClaims.isPat(jwt)) {
            return jwtRoles.resolve(jwt, userId);
        }
        Set<SocleRole> roles = new LinkedHashSet<>(patRoles.resolve(jwt, userId));
        roles.remove(SocleRole.ADMINISTRATEUR_SYSTEME);
        return Set.copyOf(roles);
    }
}
