// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

import eu.socle.user.UserIdentityService.IdentityView;
import eu.socle.user.UserIdentityService.ImportReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin — rattachement d'identités OIDC (réservé {@code ADMINISTRATEUR_SYSTEME}).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserIdentityController {

    private final UserIdentityService identityService;
    private final UserSyncService userSyncService;

    public UserIdentityController(UserIdentityService identityService, UserSyncService userSyncService) {
        this.identityService = identityService;
        this.userSyncService = userSyncService;
    }

    @GetMapping("/{id}/identities")
    public List<IdentityView> list(@PathVariable UUID id) {
        return identityService.listForUser(id);
    }

    @PostMapping("/{id}/identities")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityView link(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody LinkRequest request
    ) {
        UserEntity actor = requireActor(jwt);
        return identityService.link(actor.getId(), id, request.issuer(), request.subject());
    }

    @DeleteMapping("/{id}/identities")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestParam String issuer,
            @RequestParam String subject
    ) {
        UserEntity actor = requireActor(jwt);
        identityService.unlink(actor.getId(), id, issuer, subject);
    }

    @PostMapping(value = "/identities/import", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Map<String, Object> importIdentities(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestBody String body
    ) {
        UserEntity actor = requireActor(jwt);
        String csv = body;
        if (body != null && body.trim().startsWith("{")) {
            // JSON wrapper {"csv":"..."} optional
            csv = extractCsvFromJson(body);
        }
        ImportReport report = identityService.importIdentities(actor.getId(), csv, dryRun);
        return report.toMap();
    }

    private UserEntity requireActor(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT requis");
        }
        return userSyncService.syncFromJwt(jwt);
    }

    private static String extractCsvFromJson(String body) {
        int i = body.indexOf("\"csv\"");
        if (i < 0) {
            return body;
        }
        int colon = body.indexOf(':', i);
        int start = body.indexOf('"', colon + 1);
        if (start < 0) {
            return body;
        }
        StringBuilder sb = new StringBuilder();
        for (int p = start + 1; p < body.length(); p++) {
            char c = body.charAt(p);
            if (c == '\\' && p + 1 < body.length()) {
                char n = body.charAt(++p);
                if (n == 'n') {
                    sb.append('\n');
                } else if (n == 'r') {
                    sb.append('\r');
                } else if (n == 't') {
                    sb.append('\t');
                } else {
                    sb.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public record LinkRequest(
            @NotBlank String issuer,
            @NotBlank String subject
    ) {}
}
