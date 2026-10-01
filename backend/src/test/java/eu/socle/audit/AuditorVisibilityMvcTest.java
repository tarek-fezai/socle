// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.audit;

import eu.socle.config.SecurityWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auditeur seul : visibilité transverse sur l'activité d'un espace hors appartenance /
 * OpenFGA ; réglage {@code external_reference: restricted} n'affecte pas la lecture.
 */
@SecurityWebMvcTest(controllers = AuditController.class)
class AuditorVisibilityMvcTest {

    static final UUID FOREIGN_SPACE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DOC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired MockMvc mockMvc;
    @MockBean AuditQueryService auditQueryService;

    @Test
    void auditeurSeul_seesActivityForSpaceOutsideMembershipAndOpenFga() throws Exception {
        var event = new AuditQueryService.AuditEventView(
                42L,
                ACTOR,
                false,
                "Owner Local",
                "owner@example.com",
                AuditActions.SPACE_UPDATED,
                "space",
                FOREIGN_SPACE,
                "{\"externalReference\":\"restricted\"}",
                null,
                Instant.parse("2026-09-30T08:00:00Z").toString()
        );
        when(auditQueryService.list(
                eq("space"), eq(FOREIGN_SPACE), isNull(), isNull(),
                isNull(), isNull(), eq(0), eq(50)))
                .thenReturn(new AuditQueryService.AuditPage(List.of(event), 0, 50, 1));

        mockMvc.perform(get("/api/v1/audit")
                        .param("resourceType", "space")
                        .param("resourceId", FOREIGN_SPACE.toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].resourceId").value(FOREIGN_SPACE.toString()))
                .andExpect(jsonPath("$.items[0].action").value(AuditActions.SPACE_UPDATED))
                .andExpect(jsonPath("$.items[0].metadata").value(containsString("restricted")));

        verify(auditQueryService).list(
                eq("space"), eq(FOREIGN_SPACE), isNull(), isNull(),
                isNull(), isNull(), eq(0), eq(50));
    }

    @Test
    void restrictedSpaceActivity_stillVisible_andMetadataHasNoDocumentBody() throws Exception {
        var event = new AuditQueryService.AuditEventView(
                7L, ACTOR, false, "Owner", "o@x", AuditActions.DOCUMENT_UPDATED,
                "document", DOC,
                "{\"title\":\"Doc restreint\",\"status\":\"publie\"}", null,
                Instant.now().toString());
        when(auditQueryService.list(
                isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(0), eq(50)))
                .thenReturn(new AuditQueryService.AuditPage(List.of(event), 0, 50, 1));

        mockMvc.perform(get("/api/v1/audit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_auditeur"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].metadata").value(containsString("Doc restreint")))
                .andExpect(jsonPath("$.items[0].metadata").value(not(containsString("\"type\":\"doc\""))))
                .andExpect(jsonPath("$.items[0].metadata").value(not(containsString("SECRET"))));
    }
}
