// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.document.DocumentDraftService.DraftView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Brouillon autosave de l'appelant — jamais de version, d'audit ni d'écriture Git. */
@RestController
@RequestMapping({"/api/v1/documents/{id}/draft", "/api/documents/{id}/draft"})
public class DocumentDraftController {

    /** @param baseVersionNo version du document sur laquelle le brouillon a été commencé */
    public record PutDraftRequest(String title, Map<String, Object> body, Integer baseVersionNo) {}

    private final DocumentDraftService service;

    public DocumentDraftController(DocumentDraftService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<DraftView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PutMapping
    public DraftView put(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody PutDraftRequest request
    ) {
        return service.put(jwt, id, request.title(), request.body(), request.baseVersionNo());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(jwt, id);
    }
}
