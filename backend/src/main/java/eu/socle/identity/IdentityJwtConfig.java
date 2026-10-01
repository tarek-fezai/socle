// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import eu.socle.user.UserSyncService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Convertisseur JWT → autorités Spring via {@link RoleProvider}.
 * Séparé de {@code SecurityConfig} pour que les {@code @WebMvcTest} qui
 * importent uniquement la chaîne de filtres n'exigent pas l'identité.
 */
@Configuration
public class IdentityJwtConfig {

    @Bean
    public Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter(
            UserSyncService userSyncService,
            RoleProvider roleProvider
    ) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> authorities(jwt, userSyncService, roleProvider));
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(
            Jwt jwt,
            UserSyncService userSyncService,
            RoleProvider roleProvider
    ) {
        var user = userSyncService.syncFromJwt(jwt);
        return roleProvider.resolve(jwt, user.getId()).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()))
                .collect(Collectors.toList());
    }
}
