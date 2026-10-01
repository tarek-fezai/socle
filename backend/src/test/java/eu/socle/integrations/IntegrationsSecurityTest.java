// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.integrations;

import eu.socle.audit.AuditController;
import eu.socle.audit.AuditQueryService;
import eu.socle.config.SecurityConfig;
import eu.socle.webhook.WebhookDeliveryController;
import eu.socle.webhook.WebhookDeliveryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preuve SoD au niveau du <strong>filtre Spring Security</strong> ({@link SecurityConfig}),
 * pas un mock de rôle côté client.
 *
 * <p>{@code @WebMvcTest} + {@code @Import(SecurityConfig)} charge le vrai
 * {@code SecurityFilterChain}. {@code jwt().authorities(ROLE_*)} simule un JWT déjà
 * authentifié avec des authorities realm — le même mécanisme que
 * {@code JwtAuthenticationConverter} en prod. Les services métier sont mockés uniquement
 * pour isoler la couche HTTP/sécurité ; un 403 doit survenir <em>avant</em> tout appel service.
 */
@WebMvcTest(controllers = {
        SiemConnectorController.class,
        WebhookEndpointController.class,
        AuditController.class,
        WebhookDeliveryController.class
})
@Import(SecurityConfig.class)
class IntegrationsSecurityTest {

    static final UUID ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    @Autowired MockMvc mockMvc;

    @MockBean SiemConnectorService siemService;
    @MockBean WebhookEndpointService webhookService;
    @MockBean AuditQueryService auditQueryService;
    @MockBean WebhookDeliveryQueryService deliveryQueryService;

    @Test
    void siem_contributeur_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/siem-connectors")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void webhook_contributeur_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://x\",\"subscribedEvents\":[\"a\"]}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isForbidden());
    }

    /** Test central SoD : un auditeur ne peut pas configurer les destinations d'audit. */
    @Test
    void siem_auditeurSeul_cannotCreate_filterReturns403_beforeService() throws Exception {
        mockMvc.perform(post("/api/v1/siem-connectors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"splunk","config":{"endpoint":"https://x","hec_token":"secret"}}
                                """)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(siemService);
    }

    @Test
    void webhook_auditeurSeul_cannotCreate_filterReturns403_beforeService() throws Exception {
        mockMvc.perform(post("/api/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://hooks.example","subscribedEvents":["document.published"]}
                                """)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(webhookService);
    }

    /** SoD inverse : un intégrateur ne lit pas le journal de conformité. */
    @Test
    void audit_integrateurSeul_forbidden_filterReturns403_beforeService() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_integrateur"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(auditQueryService);
    }

    @Test
    void siem_integrateur_canCreate() throws Exception {
        when(siemService.create(anyString(), anyMap())).thenReturn(
                new SiemConnectorService.ConnectorView(
                        ID, "splunk", Map.of("endpoint", "https://x", "hec_token_prefix", "super-se…"),
                        "disconnected", null));

        mockMvc.perform(post("/api/v1/siem-connectors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"splunk","config":{"endpoint":"https://x","hec_token":"secret"}}
                                """)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_integrateur"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.config.hec_token").doesNotExist())
                .andExpect(jsonPath("$.config.hec_token_prefix").value("super-se…"));

        verify(siemService).create(eq("splunk"), anyMap());
    }

    @Test
    void webhook_integrateur_createReturnsSecret() throws Exception {
        var endpoint = new WebhookEndpointService.EndpointView(
                ID, "https://hooks.example", List.of("document.published"), "active", null);
        when(webhookService.create(anyString(), any(), isNull()))
                .thenReturn(new WebhookEndpointService.CreateResult(endpoint, "whsec_abc", "whsec_ab…"));

        mockMvc.perform(post("/api/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://hooks.example","subscribedEvents":["document.published"]}
                                """)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_integrateur"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value("whsec_abc"))
                .andExpect(jsonPath("$.endpoint.url").value("https://hooks.example"));
    }

    @Test
    void deliveries_auditeur_and_integrateur_canRead() throws Exception {
        when(deliveryQueryService.list(nullable(UUID.class), nullable(String.class), anyInt(), anyInt()))
                .thenReturn(new WebhookDeliveryQueryService.DeliveryPage(List.of(), 0, 50, 0));

        mockMvc.perform(get("/api/v1/webhooks/deliveries")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/webhooks/deliveries")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_integrateur"))))
                .andExpect(status().isOk());
    }

    @Test
    void deliveries_contributeur_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/deliveries")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutToken_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/siem-connectors")).andExpect(status().isUnauthorized());
    }
}
