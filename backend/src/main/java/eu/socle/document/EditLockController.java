// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.document.EditLockService.EditLockView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/documents/{id}/edit-lock", "/api/documents/{id}/edit-lock"})
public class EditLockController {

    private final EditLockService editLockService;

    public EditLockController(EditLockService editLockService) {
        this.editLockService = editLockService;
    }

    @GetMapping
    public EditLockView status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return editLockService.status(jwt, id);
    }

    @PostMapping
    public EditLockView acquire(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return editLockService.acquire(jwt, id);
    }

    @PostMapping("/heartbeat")
    public EditLockView heartbeat(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return editLockService.heartbeat(jwt, id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        editLockService.release(jwt, id);
    }
}
