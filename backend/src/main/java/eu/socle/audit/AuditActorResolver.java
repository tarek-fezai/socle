// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.audit;

import eu.socle.identity.IdentityClaimsMapper;
import eu.socle.user.UserIdentityService;
import jakarta.annotation.PostConstruct;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Résolution acteur depuis le JWT courant via {@code user_identities} —
 * ne suppose plus que {@code sub} est un UUID Socle.
 */
@Component
public class AuditActorResolver {

    private final IdentityClaimsMapper claimsMapper;
    private final UserIdentityService identityService;

    public AuditActorResolver(IdentityClaimsMapper claimsMapper, UserIdentityService identityService) {
        this.claimsMapper = claimsMapper;
        this.identityService = identityService;
    }

    @PostConstruct
    void registerStaticBridge() {
        AuditActors.bind(this);
    }

    public Optional<UUID> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        String subject = claimsMapper.subject(jwt);
        String issuer = claimsMapper.issuer(jwt);
        if (subject == null || issuer == null) {
            return Optional.empty();
        }
        return identityService.findUserByIdentity(issuer, subject).map(u -> u.getId());
    }
}
