// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Convertisseur JWT → autorités Spring via {@link RoleProvider}.
 * Séparé de {@code SecurityConfig} pour que les {@code @WebMvcTest} qui
 * importent uniquement la chaîne de filtres n'exigent pas l'identité.
 *
 * <p>Les refus de politique / licence levés pendant le sync sont stashed dans la requête
 * et consommés par {@link AccessPolicyFilter} (403) — jamais un HTTP 500.
 */
@Configuration
public class IdentityJwtConfig {

    private static final Logger log = LoggerFactory.getLogger(IdentityJwtConfig.class);

    /** Attribut requête : motif de refus levé pendant {@link UserSyncService#syncFromJwt}. */
    public static final String SYNC_DENIED_REASON_ATTR = "eu.socle.identity.syncDeniedReason";

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
        UserEntity user;
        try {
            user = userSyncService.syncFromJwt(jwt);
        } catch (AccessPolicyDeniedException denied) {
            stashDenied(denied);
            return List.of();
        } catch (CodedStatusException coded) {
            // Filet : anciennes levées licence_user_limit → même chemin 403.
            if (ApiErrors.LICENCE_USER_LIMIT.equals(coded.getCode())) {
                stashDenied(new AccessPolicyDeniedException(
                        AccessDeniedReason.LICENCE_USER_LIMIT, coded.getReason()));
                return List.of();
            }
            log.warn("CodedStatusException in JWT sync code={} — mapped to invalid_token", coded.getCode());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", coded.getReason(), null), coded);
        } catch (ResponseStatusException rse) {
            // subject/issuer manquant etc. → 401, pas 500
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", rse.getReason(), null), rse);
        } catch (RuntimeException ex) {
            log.error("Unexpected failure in JWT user sync", ex);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("server_error", "identity sync failed", null), ex);
        }
        return roleProvider.resolve(jwt, user.getId()).stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()))
                .collect(Collectors.toList());
    }

    private static void stashDenied(AccessPolicyDeniedException denied) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.getRequest().setAttribute(SYNC_DENIED_REASON_ATTR, denied);
        }
    }
}
