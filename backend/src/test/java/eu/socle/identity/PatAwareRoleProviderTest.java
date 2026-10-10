// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatAwareRoleProviderTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void pat_stripsAdministrateurSysteme() {
        RoleProvider jwtRoles = (jwt, id) -> Set.of(SocleRole.CONTRIBUTEUR, SocleRole.ADMINISTRATEUR_SYSTEME);
        RoleProvider patRoles = (jwt, id) -> Set.of(SocleRole.CONTRIBUTEUR, SocleRole.ADMINISTRATEUR_SYSTEME);
        PatAwareRoleProvider provider = new PatAwareRoleProvider(jwtRoles, patRoles);

        Set<SocleRole> roles = provider.resolve(patJwt(), userId);

        assertThat(roles).containsExactly(SocleRole.CONTRIBUTEUR);
        assertThat(roles).doesNotContain(SocleRole.ADMINISTRATEUR_SYSTEME);
    }

    @Test
    void idpJwt_keepsAdministrateurSysteme() {
        RoleProvider jwtRoles = (jwt, id) -> Set.of(SocleRole.ADMINISTRATEUR_SYSTEME);
        RoleProvider patRoles = (jwt, id) -> Set.of();
        PatAwareRoleProvider provider = new PatAwareRoleProvider(jwtRoles, patRoles);

        Jwt idp = Jwt.withTokenValue("eyJ.idp.sig").header("alg", "RS256")
                .claim("sub", "admin")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(provider.resolve(idp, userId)).containsExactly(SocleRole.ADMINISTRATEUR_SYSTEME);
    }

    private Jwt patJwt() {
        return Jwt.withTokenValue(PatClaims.TOKEN_VALUE_PREFIX + UUID.randomUUID())
                .header("alg", "none")
                .claim("sub", "s")
                .claim(PatClaims.AUTH_METHOD, PatClaims.AUTH_METHOD_PAT)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
