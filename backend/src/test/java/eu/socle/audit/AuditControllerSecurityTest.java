package eu.socle.audit;

import eu.socle.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditController.class)
@Import(SecurityConfig.class)
class AuditControllerSecurityTest {

    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mockMvc;

    @MockBean AuditQueryService auditQueryService;

    @Test
    void withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    void withContributeurRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void withIntegrateurSeul_returns403_beforeQueryService() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_integrateur"))))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(auditQueryService);
    }

    @Test
    void withAuditeurRole_returns200() throws Exception {
        when(auditQueryService.list(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AuditQueryService.AuditPage(List.of(), 0, 50, 0));
        mockMvc.perform(get("/api/v1/audit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isOk());
    }

    @Test
    void withAuditeurRole_forwardsFilterParams() throws Exception {
        when(auditQueryService.list(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new AuditQueryService.AuditPage(List.of(), 0, 50, 0));
        mockMvc.perform(get("/api/v1/audit")
                        .param("resourceType", "document")
                        .param("action", "access.*")
                        .param("actorId", ACTOR.toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isOk());

        verify(auditQueryService).list(
                eq("document"),
                isNull(),
                eq(ACTOR),
                eq("access.*"),
                isNull(),
                isNull(),
                eq(0),
                eq(50));
    }
}
