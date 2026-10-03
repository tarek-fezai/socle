// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.document.DocumentRelatedLinksService.RelatedLinks;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{id}/links")
public class DocumentRelatedLinksController {

    private final DocumentRelatedLinksService service;

    public DocumentRelatedLinksController(DocumentRelatedLinksService service) {
        this.service = service;
    }

    /** Documents liés (sortants / entrants), filtrés par droit de lecture de l'appelant. */
    @GetMapping
    public RelatedLinks links(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.links(jwt, id);
    }
}
