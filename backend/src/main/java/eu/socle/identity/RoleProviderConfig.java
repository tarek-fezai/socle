// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        return switch (properties.getRoleSource()) {
            case CLAIMS -> claims;
            case INTERNAL -> internal;
            case BOTH -> new CompositeRoleProvider(claims, internal);
        };
    }
}
