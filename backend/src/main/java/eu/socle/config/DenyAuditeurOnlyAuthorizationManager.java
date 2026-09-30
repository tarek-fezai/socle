package eu.socle.config;

import eu.socle.identity.SocleRole;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * SoD : un JWT qui n'a que {@code ROLE_auditeur} (sans contributeur/integrateur)
 * ne peut pas muter la gouvernance locale ni exporter. Voir {@code docs/audit-logging.md}.
 */
public final class DenyAuditeurOnlyAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Set<String> OPERATOR_ROLES = Set.of(
            SocleRole.CONTRIBUTEUR.authority(),
            SocleRole.INTEGRATEUR.authority(),
            SocleRole.ADMINISTRATEUR_SYSTEME.authority()
    );

    @Override
    public AuthorizationDecision check(
            Supplier<Authentication> authentication,
            RequestAuthorizationContext context
    ) {
        Authentication a = authentication.get();
        if (a == null || !a.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }
        Set<String> roles = a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        boolean auditeur = roles.contains(SocleRole.AUDITEUR.authority());
        if (!auditeur) {
            return new AuthorizationDecision(true);
        }
        boolean operator = roles.stream().anyMatch(OPERATOR_ROLES::contains);
        return new AuthorizationDecision(operator);
    }
}
