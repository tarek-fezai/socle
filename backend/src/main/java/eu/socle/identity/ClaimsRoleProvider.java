// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;
import java.util.UUID;

/** Rôles issus uniquement des claims JWT (comportement Keycloak par défaut). */
public class ClaimsRoleProvider implements RoleProvider {

    private final IdentityClaimsMapper claimsMapper;

    public ClaimsRoleProvider(IdentityClaimsMapper claimsMapper) {
        this.claimsMapper = claimsMapper;
    }

    @Override
    public Set<SocleRole> resolve(Jwt jwt, UUID userId) {
        return claimsMapper.rolesFromClaims(jwt);
    }
}
