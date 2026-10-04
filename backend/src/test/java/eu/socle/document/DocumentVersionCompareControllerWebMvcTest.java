// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.config.SecurityWebMvcTest;
import eu.socle.document.DocumentDtos.CompareHunk;
import eu.socle.document.DocumentDtos.CompareLine;
import eu.socle.document.DocumentDtos.CompareSpan;
import eu.socle.document.DocumentDtos.VersionCompareResponse;
import eu.socle.web.ApiErrors;
import eu.socle.web.ApiExceptionHandler;
import eu.socle.workflowdef.ApprovalWorkflowDefinitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SecurityWebMvcTest(controllers = DocumentController.class)
@Import(ApiExceptionHandler.class)
class DocumentVersionCompareControllerWebMvcTest {

    static final UUID DOC = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired MockMvc mockMvc;
    @MockBean DocumentService service;
    @MockBean DocumentApprovalService approvalService;
    @MockBean ApprovalWorkflowDefinitionService workflowDefinitions;
    @MockBean DocumentViewService documentViewService;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/documents/{id}/versions/1/compare/2", DOC))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void compare_jsonShape_defaultsToLinesMode_andOmitsAbsentSpans() throws Exception {
        VersionCompareResponse body = new VersionCompareResponse(DOC, 1, 2, 1, 1, List.of(
                new CompareHunk("Intro", List.of(
                        new CompareLine("context", 4, 4, "a", null),
                        new CompareLine("del", 5, null, "chat", List.of(
                                new CompareSpan("del", "chat"))),
                        new CompareLine("add", null, 5, "chien", null)), 0),
                new CompareHunk("Intro", List.of(), 12)));
        when(service.compare(any(), eq(DOC), eq(1), eq(2), eq("lines"))).thenReturn(body);

        mockMvc.perform(get("/api/v1/documents/{id}/versions/1/compare/2", DOC).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(DOC.toString()))
                .andExpect(jsonPath("$.fromVersion").value(1))
                .andExpect(jsonPath("$.toVersion").value(2))
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.removed").value(1))
                .andExpect(jsonPath("$.hunks[0].header").value("Intro"))
                .andExpect(jsonPath("$.hunks[0].collapsedUnchanged").value(0))
                .andExpect(jsonPath("$.hunks[0].lines[0].kind").value("context"))
                .andExpect(jsonPath("$.hunks[0].lines[0].spans").doesNotExist())
                .andExpect(jsonPath("$.hunks[0].lines[1].newNo").value((Object) null))
                .andExpect(jsonPath("$.hunks[0].lines[1].spans[0].kind").value("del"))
                .andExpect(jsonPath("$.hunks[1].lines").isEmpty())
                .andExpect(jsonPath("$.hunks[1].collapsedUnchanged").value(12));
    }

    @Test
    void compare_413_and404_andMode_arePropagated() throws Exception {
        when(service.compare(any(), eq(DOC), eq(1), eq(2), eq("lines")))
                .thenThrow(ApiErrors.diffTooLarge(2, 12_000, 8_000));
        mockMvc.perform(get("/api/v1/documents/{id}/versions/1/compare/2?mode=lines", DOC).with(jwt()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ApiErrors.DIFF_TOO_LARGE))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("trop volumineuse")));

        when(service.compare(any(), eq(DOC), eq(1), eq(9), eq("lines")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Version 9 introuvable"));
        mockMvc.perform(get("/api/v1/documents/{id}/versions/1/compare/9", DOC).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Version 9 introuvable"));
    }

    @Test
    void bodyDiffRoute_isStillMapped() throws Exception {
        when(service.diff(any(), eq(DOC), eq(1), eq(2)))
                .thenReturn(new DocumentDtos.VersionDiffResponse(DOC, 1, 2, List.of(
                        new DocumentDtos.DiffChange("/content/0", "modified", "a", "b"))));
        mockMvc.perform(get("/api/v1/documents/{id}/versions/1/diff/2", DOC).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].path").value("/content/0"))
                .andExpect(jsonPath("$.changes[0].op").value("modified"));
    }
}
