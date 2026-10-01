// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.config;

import eu.socle.identity.SocleRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

/**
 * OAuth2 resource server — JWT + rôles plateforme ({@code ROLE_*}).
 *
 * <p>Séparation des tâches (SoD) :
 * <ul>
 *   <li>{@code auditeur} — supervision / lecture du journal (transverse, metadata-only)</li>
 *   <li>{@code integrateur} — configuration SIEM / webhooks (ne lit pas le journal seul)</li>
 *   <li>auditeur <strong>seul</strong> — interdit de muter Spaces/Groupes/Workflows/Export</li>
 * </ul>
 *
 * <p>Le convertisseur JWT réel est fourni par {@code IdentityJwtConfig} ;
 * un fallback vide permet aux {@code @WebMvcTest} d'importer cette config seule
 * (les tests posent les authorities via {@code jwt().authorities(...)}).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final AuthorizationManager<RequestAuthorizationContext> DENY_AUDITEUR_ONLY =
            new DenyAuditeurOnlyAuthorizationManager();

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<Converter<Jwt, ? extends AbstractAuthenticationToken>> jwtAuthenticationConverter
    ) throws Exception {
        Converter<Jwt, ? extends AbstractAuthenticationToken> converter =
                jwtAuthenticationConverter.getIfAvailable(SecurityConfig::fallbackJwtConverter);

        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/auth-config").permitAll()
                        .requestMatchers("/api/v1/audit", "/api/v1/audit/**")
                                .hasRole(SocleRole.AUDITEUR.springRole())
                        .requestMatchers("/api/v1/admin/**")
                                .hasRole(SocleRole.ADMINISTRATEUR_SYSTEME.springRole())
                        .requestMatchers(
                                "/api/v1/siem-connectors",
                                "/api/v1/siem-connectors/**",
                                "/api/v1/webhook-endpoints",
                                "/api/v1/webhook-endpoints/**"
                        ).hasRole(SocleRole.INTEGRATEUR.springRole())
                        .requestMatchers(
                                "/api/v1/webhooks/deliveries",
                                "/api/v1/webhooks/deliveries/**"
                        ).hasAnyRole(
                                SocleRole.AUDITEUR.springRole(),
                                SocleRole.INTEGRATEUR.springRole()
                        )
                        .requestMatchers(HttpMethod.POST, "/api/v1/spaces", "/api/v1/spaces/**").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/spaces/**").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/spaces/**").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.POST, "/api/v1/groups", "/api/v1/groups/**").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/groups/**").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/groups/**").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.POST, "/api/v1/approval-workflows", "/api/v1/approval-workflows/**")
                                .access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/approval-workflows/**").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/approval-workflows/**").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.POST, "/api/v1/approval-role-assignments", "/api/v1/approval-role-assignments/**")
                                .access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/approval-role-assignments/**")
                                .access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/documents/*/export").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/folders/*/export").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/tags/*/export").access(DENY_AUDITEUR_ONLY)
                        .requestMatchers(
                                "/api/documents/**",
                                "/api/v1/documents/**",
                                "/api/v1/approvals",
                                "/api/v1/approvals/**",
                                "/api/v1/approval-workflows",
                                "/api/v1/approval-workflows/**",
                                "/api/v1/global-roles",
                                "/api/v1/global-roles/**",
                                "/api/v1/approval-role-assignments",
                                "/api/v1/approval-role-assignments/**",
                                "/api/v1/notifications",
                                "/api/v1/notifications/**",
                                "/api/v1/trash",
                                "/api/v1/trash/**",
                                "/api/v1/me",
                                "/api/v1/me/**",
                                "/api/v1/comments",
                                "/api/v1/comments/**",
                                "/api/v1/access",
                                "/api/v1/access/**",
                                "/api/v1/spaces",
                                "/api/v1/spaces/**",
                                "/api/v1/groups",
                                "/api/v1/groups/**",
                                "/api/v1/search",
                                "/api/v1/search/**",
                                "/api/v1/folders",
                                "/api/v1/folders/**",
                                "/api/v1/tags",
                                "/api/v1/tags/**"
                        ).authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));

        return http.build();
    }

    private static Converter<Jwt, ? extends AbstractAuthenticationToken> fallbackJwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        return converter;
    }
}
