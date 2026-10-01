// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.folder;

import eu.socle.folder.FolderDtos.CreateFolderRequest;
import eu.socle.folder.FolderDtos.FolderView;
import eu.socle.folder.FolderDtos.MoveDocumentRequest;
import eu.socle.folder.FolderDtos.MoveFolderRequest;
import eu.socle.folder.FolderDtos.SpaceTreeResponse;
import eu.socle.folder.FolderDtos.UpdateFolderRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class FolderController {

    private final FolderService service;

    public FolderController(FolderService service) {
        this.service = service;
    }

    @PostMapping({"/api/v1/folders", "/api/folders"})
    @ResponseStatus(HttpStatus.CREATED)
    public FolderView create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateFolderRequest body
    ) {
        return service.create(jwt, body);
    }

    @GetMapping({"/api/v1/folders/{id}", "/api/folders/{id}"})
    public FolderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(jwt, id);
    }

    @PatchMapping({"/api/v1/folders/{id}", "/api/folders/{id}"})
    public FolderView update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFolderRequest body
    ) {
        return service.update(jwt, id, body);
    }

    @DeleteMapping({"/api/v1/folders/{id}", "/api/folders/{id}"})
    public Map<String, Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        var r = service.delete(jwt, id);
        return Map.of("documents", r.documents(), "folders", r.folders(), "spaces", r.spaces());
    }

    @PostMapping({"/api/v1/folders/{id}/move", "/api/folders/{id}/move"})
    public FolderView moveFolder(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody MoveFolderRequest body
    ) {
        return service.move(jwt, id, body);
    }

    @GetMapping("/api/v1/spaces/{id}/tree")
    public SpaceTreeResponse tree(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam(required = false) Integer depth
    ) {
        return service.tree(jwt, id, depth);
    }

    @PostMapping({"/api/v1/documents/{id}/move", "/api/documents/{id}/move"})
    public Map<String, Object> moveDocument(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody MoveDocumentRequest body
    ) {
        return service.moveDocument(jwt, id, body);
    }
}
