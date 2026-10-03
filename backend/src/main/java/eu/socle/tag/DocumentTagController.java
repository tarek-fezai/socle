// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.tag;

import eu.socle.document.DocumentDtos.TagRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{id}/tags")
public class DocumentTagController {

    /** Exactement un de {@code tagId} (étiquette existante) ou {@code name} (créée si nouvelle). */
    public record AddTagRequest(UUID tagId, String name) {}

    private final TagService service;

    public DocumentTagController(TagService service) {
        this.service = service;
    }

    /** 201 si rattachée, 200 si l'étiquette était déjà présente (idempotent). */
    @PostMapping
    public ResponseEntity<TagRef> add(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody AddTagRequest request
    ) {
        TagService.Attachment attachment = service.attach(jwt, id, request.tagId(), request.name());
        return ResponseEntity
                .status(attachment.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(attachment.tag());
    }

    @DeleteMapping("/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @PathVariable UUID tagId
    ) {
        service.detach(jwt, id, tagId);
    }
}
