// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Admin — désactivation / réactivation de comptes (réservé {@code ADMINISTRATEUR_SYSTEME}
 * via {@code /api/v1/admin/**}).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserAccountController {

    private final UserAccountService accountService;
    private final UserSyncService userSyncService;

    public UserAccountController(UserAccountService accountService, UserSyncService userSyncService) {
        this.accountService = accountService;
        this.userSyncService = userSyncService;
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UserEntity actor = requireActor(jwt);
        return view(accountService.disable(actor.getId(), id));
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UserEntity actor = requireActor(jwt);
        return view(accountService.enable(actor.getId(), id));
    }

    private UserEntity requireActor(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT requis");
        }
        return userSyncService.syncFromJwt(jwt);
    }

    private static Map<String, Object> view(UserEntity user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("status", user.getStatus());
        return body;
    }
}
