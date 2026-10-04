// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.web;

import eu.socle.config.SecurityWebMvcTest;
import eu.socle.document.DocumentDraftController;
import eu.socle.document.DocumentDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 401 / 403 inchangés (filtre Security / ResponseStatusException métier). */
@SecurityWebMvcTest(controllers = DocumentDraftController.class)
@Import(ApiExceptionHandler.class)
class ApiExceptionHandlerSecurityWebMvcTest {

    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mockMvc;
    @MockBean DocumentDraftService service;

    @Test
    void unauthenticated_still401() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}/draft", DOC))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forbidden_still403() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"))
                .when(service).delete(any(), eq(DOC));
        mockMvc.perform(delete("/api/v1/documents/{id}/draft", DOC).with(jwt()))
                .andExpect(status().isForbidden());
    }
}
