// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.folder;

import eu.socle.document.DocumentDtos.DocumentResponse;
import eu.socle.folder.FolderDtos.SaveFolderPageRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class FolderPageController {

    private final FolderPageService service;

    public FolderPageController(FolderPageService service) {
        this.service = service;
    }

    @GetMapping({"/api/v1/folders/{id}/page", "/api/folders/{id}/page"})
    public ResponseEntity<DocumentResponse> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        DocumentResponse page = service.get(jwt, id);
        if (page == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(page);
    }

    @PutMapping({"/api/v1/folders/{id}/page", "/api/folders/{id}/page"})
    public DocumentResponse save(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody SaveFolderPageRequest body
    ) {
        return service.save(jwt, id, body);
    }
}
