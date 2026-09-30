package eu.socle.identity;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Rôles stockés en base ({@code user_platform_roles}) + rôle par défaut. */
public class InternalRoleProvider implements RoleProvider {

    private final PlatformRoleRepository platformRoleRepository;
    private final IdentityProperties properties;

    public InternalRoleProvider(
            PlatformRoleRepository platformRoleRepository,
            IdentityProperties properties
    ) {
        this.platformRoleRepository = platformRoleRepository;
        this.properties = properties;
    }

    @Override
    public Set<SocleRole> resolve(Jwt jwt, UUID userId) {
        Set<SocleRole> roles = EnumSet.noneOf(SocleRole.class);
        SocleRole defaultRole = properties.getDefaultRole();
        if (defaultRole != null) {
            roles.add(defaultRole);
        }
        if (userId == null) {
            return roles;
        }
        for (PlatformRoleEntity row : platformRoleRepository.findByUserId(userId)) {
            try {
                roles.add(SocleRole.valueOf(row.getRole()));
            } catch (IllegalArgumentException ignored) {
                // rôle inconnu en base — ignoré
            }
        }
        return roles;
    }
}
