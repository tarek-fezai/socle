// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.feedback;

import eu.socle.feedback.FeedbackDtos.FeedbackView;
import eu.socle.feedback.FeedbackDtos.PutFeedbackRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{id}/feedback")
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    /** Upsert du vote de l'utilisateur courant. */
    @PutMapping
    public FeedbackView put(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody PutFeedbackRequest request
    ) {
        return service.put(jwt, id, request.helpful());
    }

    /** Vote courant ; totaux oui/non uniquement pour les éditeurs du document. */
    @GetMapping
    public FeedbackView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(jwt, id);
    }
}
