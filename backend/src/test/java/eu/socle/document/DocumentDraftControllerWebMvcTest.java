// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.config.SecurityWebMvcTest;
import eu.socle.document.DocumentDraftService.DraftView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SecurityWebMvcTest(controllers = DocumentDraftController.class)
class DocumentDraftControllerWebMvcTest {

    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mockMvc;
    @MockBean DocumentDraftService service;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}/draft", DOC)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/documents/{id}/draft", DOC)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/documents/{id}/draft", DOC)).andExpect(status().isUnauthorized());
    }

    @Test
    void put_thenGet_jsonShape() throws Exception {
        DraftView view = new DraftView("Titre", Map.of("type", "doc"), 4, Instant.parse("2026-10-03T10:00:00Z"));
        when(service.put(any(), eq(DOC), eq("Titre"), any(), eq(4))).thenReturn(view);
        when(service.get(any(), eq(DOC))).thenReturn(view);

        mockMvc.perform(put("/api/v1/documents/{id}/draft", DOC).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Titre\",\"body\":{\"type\":\"doc\"},\"baseVersionNo\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Titre"))
                .andExpect(jsonPath("$.baseVersionNo").value(4))
                .andExpect(jsonPath("$.body.type").value("doc"))
                .andExpect(jsonPath("$.updatedAt").exists());
        mockMvc.perform(get("/api/v1/documents/{id}/draft", DOC).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Titre"));
    }

    @Test
    void errors_map409_404_403_andDelete204() throws Exception {
        when(service.put(any(), eq(DOC), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Verrou d'édition requis"));
        mockMvc.perform(put("/api/v1/documents/{id}/draft", DOC).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":{},\"baseVersionNo\":1}"))
                .andExpect(status().isConflict());

        when(service.get(any(), eq(DOC)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Brouillon introuvable"));
        mockMvc.perform(get("/api/v1/documents/{id}/draft", DOC).with(jwt()))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/documents/{id}/draft", DOC).with(jwt()))
                .andExpect(status().isNoContent());
        verify(service).delete(any(), eq(DOC));

        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(service).delete(any(), eq(UUID.fromString("22222222-2222-2222-2222-222222222222")));
        mockMvc.perform(delete("/api/v1/documents/{id}/draft", "22222222-2222-2222-2222-222222222222").with(jwt()))
                .andExpect(status().isForbidden());
    }
}
