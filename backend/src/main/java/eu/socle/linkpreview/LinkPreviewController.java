// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.linkpreview;

import eu.socle.linkpreview.LinkPreviewDtos.FetchRequest;
import eu.socle.linkpreview.LinkPreviewDtos.PreviewView;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/link-previews")
public class LinkPreviewController {

    private final LinkPreviewService service;

    public LinkPreviewController(LinkPreviewService service) {
        this.service = service;
    }

    /**
     * Si le feature est désactivé : carte locale (URL + domaine), aucun appel sortant.
     * Si activé : whitelist + anti-SSRF, cache, vignette éventuelle rattachée au document.
     */
    @PostMapping
    public PreviewView fetch(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody FetchRequest request,
            @RequestParam(required = false) UUID documentId
    ) {
        return service.fetch(jwt, request.url(), documentId);
    }
}
