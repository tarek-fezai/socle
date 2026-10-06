// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import eu.socle.user.UserEntity;
import eu.socle.user.UserIdentityService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Évalue la politique d'accès ({@code socle.identity.access-policy}) pour un JWT déjà validé.
 *
 * <p>Évaluation <strong>sans effet de bord</strong> : ne crée jamais de compte, n'écrit rien
 * (l'audit et la création relèvent de {@code UserSyncService} / {@code AccessPolicyFilter}).
 * Les claims ne sont lus que via {@link IdentityClaimsMapper}.
 */
@Service
public class AccessPolicyService {

    /** Résultat d'évaluation ; {@code reason == null} ⇔ accès accordé. */
    public record Decision(AccessDeniedReason reason) {

        public static final Decision GRANTED = new Decision(null);

        public static Decision denied(AccessDeniedReason reason) {
            return new Decision(reason);
        }

        public boolean granted() {
            return reason == null;
        }
    }

    private final IdentityProperties properties;
    private final IdentityClaimsMapper claimsMapper;
    private final UserIdentityService identityService;

    public AccessPolicyService(
            IdentityProperties properties,
            IdentityClaimsMapper claimsMapper,
            UserIdentityService identityService
    ) {
        this.properties = properties;
        this.claimsMapper = claimsMapper;
        this.identityService = identityService;
    }

    /** Évalue en résolvant le compte existant via {@code user_identities (issuer, sub)}. */
    public Decision evaluate(Jwt jwt) {
        String issuer = claimsMapper.issuer(jwt);
        String subject = claimsMapper.subject(jwt);
        Optional<UserEntity> existing = (issuer == null || subject == null)
                ? Optional.empty()
                : identityService.findUserByIdentity(issuer, subject);
        return evaluate(jwt, existing.orElse(null));
    }

    /**
     * @param existing compte déjà connu pour cette identité (ou rattaché par e-mail vérifié) ;
     *                 {@code null} si aucun compte n'existe
     */
    public Decision evaluate(Jwt jwt, UserEntity existing) {
        IdentityProperties.AccessPolicy policy = properties.getAccessPolicy();

        if (existing != null && isDisabled(existing)) {
            return Decision.denied(AccessDeniedReason.ACCOUNT_DISABLED);
        }

        if (!emailDomainAllowed(jwt, policy)) {
            return Decision.denied(AccessDeniedReason.EMAIL_DOMAIN_NOT_ALLOWED);
        }

        return switch (policy.getMode()) {
            case JIT -> Decision.GRANTED;
            case REQUIRE_GROUP -> inAllowedGroup(jwt, policy)
                    ? Decision.GRANTED
                    : Decision.denied(AccessDeniedReason.NOT_IN_ALLOWED_GROUP);
            case PROVISIONED_ONLY -> existing != null
                    ? Decision.GRANTED
                    : Decision.denied(AccessDeniedReason.NOT_PROVISIONED);
        };
    }

    public static boolean isDisabled(UserEntity user) {
        return "disabled".equalsIgnoreCase(user.getStatus());
    }

    private boolean emailDomainAllowed(Jwt jwt, IdentityProperties.AccessPolicy policy) {
        Set<String> allowed = normalizedDomains(policy.getAllowedEmailDomains());
        if (allowed.isEmpty()) {
            return true;
        }
        if (!claimsMapper.emailVerified(jwt)) {
            return false;
        }
        String email = claimsMapper.email(jwt);
        if (email == null) {
            return false;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        return allowed.contains(domain);
    }

    private boolean inAllowedGroup(Jwt jwt, IdentityProperties.AccessPolicy policy) {
        List<String> allowedGroups = policy.getAllowedGroups();
        if (allowedGroups == null || allowedGroups.isEmpty()) {
            // Fail-closed : require-group sans groupe autorisé ne laisse personne entrer.
            return false;
        }
        List<String> tokenGroups = claimsMapper.groups(jwt);
        if (tokenGroups.isEmpty()) {
            return false;
        }
        Set<String> allowed = allowedGroups.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());
        return tokenGroups.stream().anyMatch(allowed::contains);
    }

    private static Set<String> normalizedDomains(List<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        return raw.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .map(d -> d.startsWith("@") ? d.substring(1) : d)
                .collect(Collectors.toSet());
    }
}
