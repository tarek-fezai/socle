package eu.socle.user;

import eu.socle.config.SocleProperties;
import eu.socle.identity.IdentityClaimsMapper;
import eu.socle.identity.IdentityFacade;
import eu.socle.identity.SocleRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final IdentityFacade identityFacade;
    private final IdentityClaimsMapper claimsMapper;
    private final SocleProperties socleProperties;

    public MeController(
            IdentityFacade identityFacade,
            IdentityClaimsMapper claimsMapper,
            SocleProperties socleProperties
    ) {
        this.identityFacade = identityFacade;
        this.claimsMapper = claimsMapper;
        this.socleProperties = socleProperties;
    }

    @GetMapping
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT requis");
        }
        UserEntity user = identityFacade.sync(jwt);
        List<String> roles = identityFacade.roles(jwt).stream()
                .map(SocleRole::name)
                .sorted()
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("email", user.getEmail());
        body.put("displayName", user.getDisplayName());
        body.put("avatarInitials", user.getAvatarInitials() != null ? user.getAvatarInitials() : "");
        body.put("sub", claimsMapper.subject(jwt));
        body.put("issuer", claimsMapper.issuer(jwt));
        body.put("roles", roles);
        SocleProperties.Instance instance = socleProperties.instance();
        body.put("organizationName", instance == null ? "Socle" : instance.effectiveDisplayName());
        return body;
    }

    /** Résout l'UUID Socle (pas le subject IdP) via sync. */
    public static UUID requireUserId(Jwt jwt, IdentityFacade identityFacade) {
        return identityFacade.sync(jwt).getId();
    }
}
