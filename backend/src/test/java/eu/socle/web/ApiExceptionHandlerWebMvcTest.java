// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.web;

import eu.socle.document.ApprovalConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    }
}
