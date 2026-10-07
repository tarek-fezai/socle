// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.admin;

import eu.socle.admin.AdminOverviewDtos.AdminOverviewView;
import eu.socle.admin.AdminOverviewDtos.OidcSummaryView;
import eu.socle.admin.AdminOverviewDtos.PlanSummaryView;
import eu.socle.config.SecurityWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SecurityWebMvcTest(controllers = AdminOverviewController.class)
class AdminOverviewControllerWebMvcTest {

    @Autowired MockMvc mockMvc;
    @MockBean AdminOverviewService service;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdmin_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_returnsOverview() throws Exception {
        when(service.overview(any())).thenReturn(new AdminOverviewView(
                new OidcSummaryView("https://idp.example/realms/socle", "socle-frontend", "connected"),
                12L,
                3L,
                new PlanSummaryView(false, "Entreprise", Instant.parse("2027-01-01T00:00:00Z"))));

        mockMvc.perform(get("/api/v1/admin/overview").with(jwt().authorities(
                        new SimpleGrantedAuthority("ROLE_administrateur-systeme"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oidc.issuer").value("https://idp.example/realms/socle"))
                .andExpect(jsonPath("$.oidc.clientId").value("socle-frontend"))
                .andExpect(jsonPath("$.oidc.status").value("connected"))
                .andExpect(jsonPath("$.userCount").value(12))
                .andExpect(jsonPath("$.spaceCount").value(3))
                .andExpect(jsonPath("$.plan.evaluationMode").value(false))
                .andExpect(jsonPath("$.plan.edition").value("Entreprise"));
    }
}
