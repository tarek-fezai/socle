// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.tag;

import eu.socle.config.SecurityWebMvcTest;
import eu.socle.customfield.DocumentCustomFieldController;
import eu.socle.customfield.DocumentCustomFieldService;
import eu.socle.document.DocumentDtos.TagRef;
import eu.socle.document.WritingAssistantController;
import eu.socle.document.WritingAssistantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routes, authentification et formes JSON des endpoints de l'écran d'édition. */
@SecurityWebMvcTest(controllers = {
        TagController.class,
        DocumentTagController.class,
        DocumentCustomFieldController.class,
        WritingAssistantController.class
})
class EditScreenControllersWebMvcTest {

    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID TAG = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID FIELD = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Autowired MockMvc mockMvc;

    @MockBean TagService tagService;
    @MockBean DocumentCustomFieldService customFieldService;
    @MockBean WritingAssistantService writingAssistantService;

    @Test
    void allEndpoints_requireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/tags")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/documents/{id}/tags", DOC)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/documents/{id}/tags/{t}", DOC, TAG)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{id}/custom-fields", DOC)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{id}/writing-assistant", DOC)).andExpect(status().isUnauthorized());
    }

    @Test
    void searchTags_returnsIdNameColor() throws Exception {
        when(tagService.search(any(), eq("rg"), isNull()))
                .thenReturn(List.of(new TagRef(TAG, "RGPD", "#f00", true)));

        mockMvc.perform(get("/api/v1/tags").param("q", "rg").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(TAG.toString()))
                .andExpect(jsonPath("$[0].name").value("RGPD"))
                .andExpect(jsonPath("$[0].color").value("#f00"))
                .andExpect(jsonPath("$[0].governed").value(true));
    }

    @Test
    void addTag_returns201WhenCreated_and200WhenAlreadyAttached() throws Exception {
        TagRef tag = new TagRef(TAG, "RGPD", null, false);
        when(tagService.attach(any(), eq(DOC), isNull(), eq("RGPD")))
                .thenReturn(new TagService.Attachment(tag, true));
        when(tagService.attach(any(), eq(DOC), eq(TAG), isNull()))
                .thenReturn(new TagService.Attachment(tag, false));

        mockMvc.perform(post("/api/v1/documents/{id}/tags", DOC).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"RGPD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("RGPD"));
        mockMvc.perform(post("/api/v1/documents/{id}/tags", DOC).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tagId\":\"" + TAG + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void addTag_forbidden_is403() throws Exception {
        when(tagService.attach(any(), eq(DOC), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Accès refusé (editor)"));

        mockMvc.perform(post("/api/v1/documents/{id}/tags", DOC).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"RGPD\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void removeTag_returns204_andUnknownDocument404() throws Exception {
        mockMvc.perform(delete("/api/v1/documents/{id}/tags/{t}", DOC, TAG).with(jwt()))
                .andExpect(status().isNoContent());
        verify(tagService).detach(any(), eq(DOC), eq(TAG));

        UUID unknown = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Document introuvable"))
                .when(tagService).detach(any(), eq(unknown), eq(TAG));
        mockMvc.perform(delete("/api/v1/documents/{id}/tags/{t}", unknown, TAG).with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void customFields_emptyListAndPut() throws Exception {
        when(customFieldService.list(any(), eq(DOC))).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/documents/{id}/custom-fields", DOC).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        when(customFieldService.setValue(any(), eq(DOC), eq(FIELD), any()))
                .thenReturn(new DocumentCustomFieldService.CustomFieldView(
                        FIELD, "Réf", "ref", "texte", false, null,
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree("\"abc\""),
                        null));
        mockMvc.perform(put("/api/v1/documents/{id}/custom-fields/{f}", DOC, FIELD).with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("abc"))
                .andExpect(jsonPath("$.fieldType").value("texte"));
    }

    @Test
    void writingAssistant_jsonShape_andNoStore() throws Exception {
        UUID target = UUID.randomUUID();
        when(writingAssistantService.hints(any(), eq(DOC))).thenReturn(new WritingAssistantService.Hints(
                120,
                List.of(new WritingAssistantService.BrokenLink(
                        target, "Procédure de provisioning v9", false, WritingAssistantService.REASON_DELETED)),
                List.of()));

        mockMvc.perform(get("/api/v1/documents/{id}/writing-assistant", DOC).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.longParagraphThresholdWords").value(120))
                .andExpect(jsonPath("$.brokenLinks[0].targetId").value(target.toString()))
                .andExpect(jsonPath("$.brokenLinks[0].label").value("Procédure de provisioning v9"))
                .andExpect(jsonPath("$.brokenLinks[0].accessible").value(false))
                .andExpect(jsonPath("$.brokenLinks[0].reason").value("deleted"))
                .andExpect(jsonPath("$.longParagraphs").isEmpty());
    }
}
