// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.config;

import eu.socle.identity.AccessPolicyFilter;
import eu.socle.identity.SocleRole;
import eu.socle.pat.PatAuthenticationProvider;
import eu.socle.pat.PatRestrictionFilter;
import eu.socle.pat.PatTokenFormat;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
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
            ObjectProvider<Converter<Jwt, ? extends AbstractAuthenticationToken>> jwtAuthenticationConverter,
            ObjectProvider<AccessPolicyFilter> accessPolicyFilter,
            ObjectProvider<JwtDecoder> jwtDecoder,
            ObjectProvider<PatAuthenticationProvider> patAuthenticationProvider
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
                        // Branding public minimal (page de connexion avant authentification).
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/branding", "/api/v1/public/branding/*")
                                .permitAll()
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
                                "/api-docs",
                                "/api-docs/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/ApiDocs.dc.html",
                                "/ApiDocs.dc.html/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).hasRole(SocleRole.ADMINISTRATEUR_SYSTEME.springRole())
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
                                "/api/v1/tags/**",
                                "/api/v1/templates",
                                "/api/v1/templates/**",
                                "/api/v1/favorites",
                                "/api/v1/favorites/**",
                                "/api/v1/home",
                                "/api/v1/home/**",
                                "/api/v1/attachments",
                                "/api/v1/attachments/**"
                        ).authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(bearerAuthenticationManagerResolver(
                                jwtDecoder, converter, patAuthenticationProvider)));

        http.addFilterAfter(new PatRestrictionFilter(), BearerTokenAuthenticationFilter.class);
        // Politique d'accès après validation du JWT (absente des @WebMvcTest qui n'importent que cette config).
        AccessPolicyFilter policyFilter = accessPolicyFilter.getIfAvailable();
        if (policyFilter != null) {
            http.addFilterAfter(policyFilter, PatRestrictionFilter.class);
        }

        return http.build();
    }

    /**
     * Bearer {@code pat_…} → {@link PatAuthenticationProvider} (jamais présenté au décodeur JWT) ;
     * tout autre Bearer → resource server JWT inchangé. Le décodeur est résolu à la première requête
     * JWT (découverte OIDC paresseuse, comme l'auto-configuration).
     */
    static AuthenticationManagerResolver<HttpServletRequest> bearerAuthenticationManagerResolver(
            ObjectProvider<JwtDecoder> jwtDecoder,
            Converter<Jwt, ? extends AbstractAuthenticationToken> converter,
            ObjectProvider<PatAuthenticationProvider> patAuthenticationProvider
    ) {
        DefaultBearerTokenResolver bearerResolver = new DefaultBearerTokenResolver();
        AuthenticationManager jwtManager = new AuthenticationManager() {
            private volatile AuthenticationManager delegate;

            @Override
            public Authentication authenticate(Authentication authentication) {
                AuthenticationManager current = delegate;
                if (current == null) {
                    JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtDecoder.getObject());
                    provider.setJwtAuthenticationConverter(converter);
                    current = new ProviderManager(provider);
                    delegate = current;
                }
                return current.authenticate(authentication);
            }
        };
        AuthenticationManager patManager = authentication -> {
            PatAuthenticationProvider provider = patAuthenticationProvider.getIfAvailable();
            if (provider == null) {
                throw new InvalidBearerTokenException("Invalid token");
            }
            return provider.authenticate(authentication);
        };
        return request -> {
            String token;
            try {
                token = bearerResolver.resolve(request);
            } catch (RuntimeException e) {
                return jwtManager;
            }
            return token != null && token.startsWith(PatTokenFormat.PREFIX) ? patManager : jwtManager;
        };
    }

    private static Converter<Jwt, ? extends AbstractAuthenticationToken> fallbackJwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        return converter;
    }
}
