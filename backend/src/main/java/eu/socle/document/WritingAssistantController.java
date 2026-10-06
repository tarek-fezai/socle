// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.document.WritingAssistantService.Hints;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{id}/writing-assistant")
public class WritingAssistantController {

    private final WritingAssistantService service;

    public WritingAssistantController(WritingAssistantService service) {
        this.service = service;
    }

    /** Liens cassés / inaccessibles et paragraphes trop longs (viewer requis). */
    @GetMapping
    public ResponseEntity<Hints> hints(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.hints(jwt, id));
    }
}
