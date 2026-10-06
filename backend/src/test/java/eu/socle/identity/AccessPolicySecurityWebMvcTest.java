// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import eu.socle.config.SecurityWebMvcTest;
import eu.socle.config.SocleProperties;
import eu.socle.user.MeController;
import eu.socle.user.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Câblage {@link AccessPolicyFilter} dans la chaîne {@code SecurityConfig} (après Bearer). */
@SecurityWebMvcTest(controllers = MeController.class)
@Import({AccessPolicyConfig.class, AccessDecisionCache.class})
class AccessPolicySecurityWebMvcTest {

    static final String ISSUER = "http://localhost:8081/realms/socle";

    @Autowired MockMvc mockMvc;
    @Autowired AccessDecisionCache cache;

    @MockBean AccessPolicyService accessPolicyService;
    @MockBean AccessAuditService accessAuditService;
    @MockBean IdentityClaimsMapper claimsMapper;
    @MockBean IdentityFacade identityFacade;
    @MockBean SocleProperties socleProperties;

    @BeforeEach
    void setUp() {
        cache.invalidateAll();
        when(claimsMapper.subject(any())).thenReturn("sub-1");
        when(claimsMapper.issuer(any())).thenReturn(ISSUER);
    }

    @Test
    void me_deniedByPolicy_returns403JsonAndNeverReachesController() throws Exception {
        when(accessPolicyService.evaluate(any()))
                .thenReturn(AccessPolicyService.Decision.denied(AccessDeniedReason.NOT_PROVISIONED));

        mockMvc.perform(get("/api/v1/me")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("access_denied"))
                .andExpect(jsonPath("$.reason").value("not_provisioned"));

        verifyNoInteractions(identityFacade);
        verify(accessAuditService).recordDenied(ISSUER, "sub-1", null, AccessDeniedReason.NOT_PROVISIONED);
    }

    @Test
    void me_grantedByPolicy_reachesController() throws Exception {
        when(accessPolicyService.evaluate(any())).thenReturn(AccessPolicyService.Decision.GRANTED);
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("a@corp.com");
        user.setDisplayName("A");
        when(identityFacade.sync(any())).thenReturn(user);
        when(identityFacade.roles(any())).thenReturn(Set.of(SocleRole.CONTRIBUTEUR));

        mockMvc.perform(get("/api/v1/me")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }
}
