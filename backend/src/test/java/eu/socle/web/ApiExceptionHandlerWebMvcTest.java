// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.web;

import eu.socle.document.ApprovalConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MockMvcBuilders#standaloneSetup} — pas de {@code @RestController} dans le classpath
 * scanné par {@code @SpringBootTest} (évite de polluer OpenAPI).
 */
class ApiExceptionHandlerWebMvcTest {

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void approvalInProgress_409_problemDetailWithCode() throws Exception {
        mockMvc.perform(get("/api/v1/_probe/approval-in-progress"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Demande d'approbation en cours"))
                .andExpect(jsonPath("$.code").value(ApiErrors.APPROVAL_IN_PROGRESS))
                .andExpect(jsonPath("$.instance").value("/api/v1/_probe/approval-in-progress"));
    }

    @Test
    void editLockHeld_409_problemDetailWithCode() throws Exception {
        mockMvc.perform(get("/api/v1/_probe/edit-lock-held"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Document en cours d'édition par Alice : restauration impossible"))
                .andExpect(jsonPath("$.code").value(ApiErrors.EDIT_LOCK_HELD));
    }

    @Test
    void rejectJustificationRequired_400_problemDetailWithCode() throws Exception {
        mockMvc.perform(post("/api/v1/_probe/reject-without-justification"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Justification obligatoire pour un refus"))
                .andExpect(jsonPath("$.code").value(ApiErrors.REJECT_JUSTIFICATION_REQUIRED));
    }

    @Test
    void governedTagOwnerOnly_403_problemDetailWithCode() throws Exception {
        mockMvc.perform(get("/api/v1/_probe/governed-tag"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        "Étiquette gouvernée : seul un owner peut la rattacher ou la détacher"))
                .andExpect(jsonPath("$.code").value(ApiErrors.GOVERNED_TAG_OWNER_ONLY));
    }

    @Test
    void diffTooLarge_413_problemDetailWithCode() throws Exception {
        mockMvc.perform(get("/api/v1/_probe/diff-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("trop volumineuse")))
                .andExpect(jsonPath("$.code").value(ApiErrors.DIFF_TOO_LARGE));
    }

    @Test
    void approvalConflict_usesCodeProperty() throws Exception {
        mockMvc.perform(get("/api/v1/_probe/already-resolved"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Demande déjà résolue"))
                .andExpect(jsonPath("$.code").value(ApprovalConflictException.ALREADY_RESOLVED))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void invalidUuidPath_returns400_safeDetail() throws Exception {
        mockMvc.perform(get("/api/v1/_probe/items/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Paramètre invalide : id"))
                .andExpect(content().string(not(containsString("UUID"))))
                .andExpect(content().string(not(containsString("MethodArgumentTypeMismatch"))));
    }

    @Test
    void invalidJsonBody_returns400_withoutClassNames() throws Exception {
        mockMvc.perform(post("/api/v1/_probe/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Corps de requête illisible"))
                .andExpect(content().string(not(containsString("JsonParseException"))))
                .andExpect(content().string(not(containsString("HttpMessageNotReadable"))))
                .andExpect(content().string(not(containsString("com.fasterxml"))));
    }

    @Test
    void missingRequestParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/_probe/search"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Paramètre manquant : q"));
    }

    @Test
    void postOnGetOnly_returns405() throws Exception {
        mockMvc.perform(post("/api/v1/_probe/items/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Méthode non autorisée"));
    }

    @Test
    void unsupportedContentType_returns415() throws Exception {
        mockMvc.perform(post("/api/v1/_probe/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Type de contenu non supporté"));
    }

    @Test
    void multipartTooLarge_returns413_payloadTooLargeCode() throws Exception {
        mockMvc.perform(multipart("/api/v1/_probe/upload").file("file", "x".getBytes()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Fichier trop volumineux"))
                .andExpect(jsonPath("$.code").value(ApiErrors.PAYLOAD_TOO_LARGE));
    }

    @Test
    void unexpectedRuntime_500_genericWithoutInternalMessage() throws Exception {
        mockMvc.perform(get("/api/v1/_probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Erreur interne"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.detail", not(containsString("secret-interne"))))
                .andExpect(content().string(not(containsString("secret-interne"))))
                .andExpect(content().string(not(containsString("NullPointerException"))));
    }

    @RestController
    @RequestMapping("/api/v1/_probe")
    static class ProbeController {

        @GetMapping("/approval-in-progress")
        void approvalInProgress() {
            throw ApiErrors.approvalInProgress();
        }

        @GetMapping("/edit-lock-held")
        void editLockHeld() {
            throw ApiErrors.editLockHeld("Alice");
        }

        @PostMapping("/reject-without-justification")
        void rejectWithoutJustification() {
            throw ApiErrors.rejectJustificationRequired();
        }

        @GetMapping("/governed-tag")
        void governedTag() {
            throw ApiErrors.governedTagOwnerOnly();
        }

        @GetMapping("/diff-too-large")
        void diffTooLarge() {
            throw ApiErrors.diffTooLarge(3, 12_000, 8_000);
        }

        @GetMapping("/already-resolved")
        void alreadyResolved() {
            throw ApprovalConflictException.alreadyResolved();
        }

        @GetMapping("/boom")
        void boom() {
            throw new RuntimeException("secret-interne à ne jamais exposer");
        }

        @GetMapping("/items/{id}")
        Map<String, String> item(@PathVariable UUID id) {
            return Map.of("id", id.toString());
        }

        @PostMapping(path = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> echo(@RequestBody Map<String, Object> body) {
            return body;
        }

        @GetMapping("/search")
        Map<String, String> search(@RequestParam String q) {
            return Map.of("q", q);
        }

        @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        void upload() {
            throw new MaxUploadSizeExceededException(1024);
        }
    }
}
