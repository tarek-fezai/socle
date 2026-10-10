// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class RoleProviderConfig {

    @Bean
    public RoleProvider roleProvider(
            IdentityProperties properties,
            IdentityClaimsMapper claimsMapper,
            PlatformRoleRepository platformRoleRepository
    ) {
        ClaimsRoleProvider claims = new ClaimsRoleProvider(claimsMapper);
        InternalRoleProvider internal = new InternalRoleProvider(platformRoleRepository, properties);
        RoleProvider jwtRoles = switch (properties.getRoleSource()) {
            case CLAIMS -> claims;
            case INTERNAL -> internal;
            case BOTH -> new CompositeRoleProvider(claims, internal);
        };
        RoleProvider patRoles = properties.getRoleSource() == IdentityProperties.RoleSource.CLAIMS
                ? (jwt, userId) -> claimsMapper.mapRoles(List.of())
                : internal;
        return new PatAwareRoleProvider(jwtRoles, patRoles);
    }
}
