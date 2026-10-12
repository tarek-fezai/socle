// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import eu.socle.directory.DirectoryDtos.DirectoryUsersResponse;
import eu.socle.user.UserSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Annuaire nominatif — {@code GET /api/v1/directory/users}. PAT lecture autorisé.
 */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {

    private final DirectoryService directoryService;
    private final UserSyncService userSyncService;

    public DirectoryController(DirectoryService directoryService, UserSyncService userSyncService) {
        this.directoryService = directoryService;
        this.userSyncService = userSyncService;
    }

    @GetMapping("/users")
    public DirectoryUsersResponse users(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit
    ) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT requis");
        }
        var user = userSyncService.syncFromJwt(jwt);
        return directoryService.searchUsers(user.getId(), q, limit);
    }
}
