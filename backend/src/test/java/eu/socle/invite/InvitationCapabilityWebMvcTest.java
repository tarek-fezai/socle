// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.invite;

import eu.socle.config.SecurityWebMvcTest;
import eu.socle.web.ApiErrors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SecurityWebMvcTest(controllers = InvitationAdminController.class)
class InvitationCapabilityWebMvcTest {

    @Autowired MockMvc mockMvc;
    @MockBean InvitationService service;
    @MockBean InvitationCapability capability;

    @Test
    void capability_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/admin/invitations/capability"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void capability_nonAdmin_is403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/invitations/capability")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void capability_admin_returnsNamesOnly() throws Exception {
        when(capability.snapshot()).thenReturn(new InvitationCapability.Snapshot(
                false, List.of("SOCLE_PUBLIC_BASE_URL", "SOCLE_SMTP_HOST")));

        mockMvc.perform(get("/api/v1/admin/invitations/capability")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_administrateur-systeme"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.missing[0]").value("SOCLE_PUBLIC_BASE_URL"))
                .andExpect(jsonPath("$.missing[1]").value("SOCLE_SMTP_HOST"))
                .andExpect(jsonPath("$.missing.length()").value(2));
    }

    @Test
    void create_notConfigured_is503BeforeWrite() throws Exception {
        doThrow(ApiErrors.invitationsNotConfigured(List.of("SOCLE_PUBLIC_BASE_URL", "SOCLE_SMTP_HOST")))
                .when(capability).requireEnabled();

        mockMvc.perform(post("/api/v1/admin/invitations")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_administrateur-systeme")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invitee@example.com\",\"globalRole\":\"CONTRIBUTEUR\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("invitations_not_configured"))
                .andExpect(jsonPath("$.missing[0]").value("SOCLE_PUBLIC_BASE_URL"))
                .andExpect(jsonPath("$.missing[1]").value("SOCLE_SMTP_HOST"));

        verify(service, never()).create(any(), any());
    }
}
