package eu.socle.identity;

import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Façade identité : sync + résolution de rôles sans lire les claims hors mapper.
 */
@Component
public class IdentityFacade {

    private final UserSyncService userSyncService;
    private final RoleProvider roleProvider;

    public IdentityFacade(UserSyncService userSyncService, RoleProvider roleProvider) {
        this.userSyncService = userSyncService;
        this.roleProvider = roleProvider;
    }

    public UserEntity sync(Jwt jwt) {
        return userSyncService.syncFromJwt(jwt);
    }

    public Set<SocleRole> roles(Jwt jwt) {
        UserEntity user = userSyncService.syncFromJwt(jwt);
        return roleProvider.resolve(jwt, user.getId());
    }

    public boolean hasRole(Jwt jwt, SocleRole role) {
        return roles(jwt).contains(role);
    }

    public boolean isSystemAdmin(Jwt jwt) {
        return hasRole(jwt, SocleRole.ADMINISTRATEUR_SYSTEME);
    }
}
