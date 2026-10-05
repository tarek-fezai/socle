// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Union claims ∪ internal. */
public class CompositeRoleProvider implements RoleProvider {

    private final RoleProvider claims;
    private final RoleProvider internal;

    public CompositeRoleProvider(RoleProvider claims, RoleProvider internal) {
        this.claims = claims;
        this.internal = internal;
    }

    @Override
    public Set<SocleRole> resolve(Jwt jwt, UUID userId) {
        Set<SocleRole> roles = EnumSet.noneOf(SocleRole.class);
        roles.addAll(claims.resolve(jwt, userId));
        roles.addAll(internal.resolve(jwt, userId));
        return roles;
    }
}
