// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.trash;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trash")
public class TrashController {

    private final TrashService trashService;

    public TrashController(TrashService trashService) {
        this.trashService = trashService;
    }

    /**
     * Liste paginée des éléments de corbeille visibles (OpenFGA viewer+).
     * Tri : {@code deleted_at DESC}. Filtre optionnel {@code resourceType}.
     */
    @GetMapping
    public TrashService.TrashPage list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String resourceType,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return trashService.list(jwt, resourceType, offset, limit);
    }

    @GetMapping("/{trashItemId}")
    public TrashService.TrashPreview preview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID trashItemId
    ) {
        return trashService.preview(jwt, trashItemId);
    }

    @PostMapping("/{trashItemId}/restore")
    public TrashService.RestoreResult restore(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID trashItemId
    ) {
        return trashService.restore(jwt, trashItemId);
    }

    @DeleteMapping("/{trashItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purgeNow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID trashItemId
    ) {
        trashService.purgeNow(jwt, trashItemId);
    }

    @DeleteMapping("/documents/{documentId}")
    public Map<String, Object> softDeleteDocument(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID documentId
    ) {
        var r = trashService.softDeleteDocument(jwt, documentId);
        return Map.of("documents", r.documents(), "folders", r.folders(), "spaces", r.spaces());
    }

    @DeleteMapping("/folders/{folderId}")
    public Map<String, Object> softDeleteFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID folderId
    ) {
        var r = trashService.softDeleteFolder(jwt, folderId);
        return Map.of("documents", r.documents(), "folders", r.folders(), "spaces", r.spaces());
    }

    @DeleteMapping("/spaces/{spaceId}")
    public Map<String, Object> softDeleteSpace(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID spaceId
    ) {
        var r = trashService.softDeleteSpace(jwt, spaceId);
        return Map.of("documents", r.documents(), "folders", r.folders(), "spaces", r.spaces());
    }
}
