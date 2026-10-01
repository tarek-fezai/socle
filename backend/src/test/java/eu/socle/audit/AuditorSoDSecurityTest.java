// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.audit;

import eu.socle.config.SecurityConfig;
import eu.socle.document.ContentHealthService;
import eu.socle.document.TransclusionGraphService;
import eu.socle.export.ExportController;
import eu.socle.export.ExportService;
import eu.socle.integrations.SiemConnectorController;
import eu.socle.integrations.SiemConnectorService;
import eu.socle.integrations.WebhookEndpointController;
import eu.socle.integrations.WebhookEndpointService;
import eu.socle.space.SpaceController;
import eu.socle.space.SpaceService;
import eu.socle.team.GroupController;
import eu.socle.team.GroupService;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionController;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SoD généralisée : {@code auditeur} seul → 403 sur configuration Spaces / Groupes /
 * Workflows / Export / Intégrations (filtre {@link SecurityConfig}).
 */
@WebMvcTest(controllers = {
        SpaceController.class,
        GroupController.class,
        ApprovalWorkflowDefinitionController.class,
        ExportController.class,
        SiemConnectorController.class,
        WebhookEndpointController.class
})
@Import(SecurityConfig.class)
class AuditorSoDSecurityTest {

    static final UUID ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Autowired MockMvc mockMvc;

    @MockBean SpaceService spaceService;
    @MockBean TransclusionGraphService graphService;
    @MockBean ContentHealthService contentHealthService;
    @MockBean GroupService groupService;
    @MockBean ApprovalWorkflowDefinitionService workflowService;
    @MockBean ExportService exportService;
    @MockBean SiemConnectorService siemService;
    @MockBean WebhookEndpointService webhookService;

    @Test
    void spaces_auditeurSeul_cannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(spaceService);
    }

    @Test
    void spaces_auditeurSeul_cannotUpdate() throws Exception {
        mockMvc.perform(put("/api/v1/spaces/" + ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Y\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(spaceService);
    }

    @Test
    void groups_auditeurSeul_cannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"G\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(groupService);
    }

    @Test
    void groups_auditeurSeul_cannotDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/groups/" + ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(groupService);
    }

    @Test
    void workflows_auditeurSeul_cannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/approval-workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"W","status":"active","steps":[{"stepOrder":1,"approverRoleId":"%s","slaHours":24}]}
                                """.formatted(ID))
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(workflowService);
    }

    @Test
    void workflows_auditeurSeul_cannotDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/approval-workflows/" + ID)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(workflowService);
    }

    @Test
    void export_auditeurSeul_cannotExportDocument() throws Exception {
        mockMvc.perform(get("/api/v1/documents/" + ID + "/export")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(exportService);
    }

    @Test
    void export_auditeurSeul_cannotExportFolder() throws Exception {
        mockMvc.perform(get("/api/v1/folders/" + ID + "/export")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(exportService);
    }

    @Test
    void integrations_auditeurSeul_cannotConfigureSiemOrWebhook() throws Exception {
        mockMvc.perform(post("/api/v1/siem-connectors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"splunk\",\"config\":{\"endpoint\":\"https://x\"}}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://h\",\"subscribedEvents\":[\"a\"]}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(siemService);
        verifyNoInteractions(webhookService);
    }
}
