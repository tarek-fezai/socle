package eu.socle.identity;

import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/platform-roles")
public class PlatformRoleController {

    private final PlatformRoleService platformRoleService;
    private final UserSyncService userSyncService;

    public PlatformRoleController(
            PlatformRoleService platformRoleService,
            UserSyncService userSyncService
    ) {
        this.platformRoleService = platformRoleService;
        this.userSyncService = userSyncService;
    }

    @GetMapping
    public List<PlatformRoleService.PlatformRoleView> list(
            @RequestParam(required = false) UUID userId
    ) {
        if (userId != null) {
            return platformRoleService.list(userId);
        }
        return platformRoleService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformRoleService.PlatformRoleView grant(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody GrantRequest request
    ) {
        UserEntity actor = requireActor(jwt);
        SocleRole role = parseRole(request.role());
        return platformRoleService.grant(actor.getId(), request.userId(), role);
    }

    @DeleteMapping("/{userId}/{role}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userId,
            @PathVariable String role
    ) {
        UserEntity actor = requireActor(jwt);
        platformRoleService.revoke(actor.getId(), userId, parseRole(role));
    }

    private UserEntity requireActor(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT requis");
        }
        return userSyncService.syncFromJwt(jwt);
    }

    private static SocleRole parseRole(String raw) {
        try {
            return SocleRole.valueOf(raw.trim().toUpperCase().replace('-', '_'));
        } catch (Exception e) {
            // also accept spring role names
            for (SocleRole r : SocleRole.values()) {
                if (r.springRole().equalsIgnoreCase(raw) || r.name().equalsIgnoreCase(raw)) {
                    return r;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rôle inconnu: " + raw);
        }
    }

    public record GrantRequest(
            @NotNull UUID userId,
            @NotBlank String role
    ) {}
}
