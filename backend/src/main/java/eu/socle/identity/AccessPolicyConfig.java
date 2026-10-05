// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Déclare {@link AccessPolicyFilter} (consommé par {@code SecurityConfig}) et désactive son
 * enregistrement automatique dans la chaîne servlet : il ne doit tourner qu'au sein de
 * Spring Security, après {@code BearerTokenAuthenticationFilter}.
 */
@Configuration
public class AccessPolicyConfig {

    @Bean
    public AccessPolicyFilter accessPolicyFilter(
            AccessPolicyService accessPolicyService,
            AccessDecisionCache decisionCache,
            AccessAuditService accessAuditService,
            IdentityClaimsMapper claimsMapper
    ) {
        return new AccessPolicyFilter(accessPolicyService, decisionCache, accessAuditService, claimsMapper);
    }

    @Bean
    public FilterRegistrationBean<AccessPolicyFilter> accessPolicyFilterRegistration(AccessPolicyFilter filter) {
        FilterRegistrationBean<AccessPolicyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
