// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityClaimsMapperTest {

    IdentityProperties properties;
    IdentityClaimsMapper mapper;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        mapper = new IdentityClaimsMapper(properties);
    }

    @Test
    void keycloak_realmAccessRoles() {
        Jwt jwt = base()
                .claim("sub", "user-1")
                .claim("email", "a@example.com")
                .claim("name", "Alice")
                .claim("realm_access", Map.of("roles", List.of("auditeur", "contributeur")))
                .build();

        assertThat(mapper.subject(jwt)).isEqualTo("user-1");
        assertThat(mapper.email(jwt)).isEqualTo("a@example.com");
        assertThat(mapper.name(jwt)).isEqualTo("Alice");
        assertThat(mapper.rawRoleValues(jwt)).containsExactlyInAnyOrder("auditeur", "contributeur");
        assertThat(mapper.rolesFromClaims(jwt))
                .containsExactlyInAnyOrder(SocleRole.AUDITEUR, SocleRole.CONTRIBUTEUR);
    }

    @Test
    void entra_rolesAndOid() {
        properties.setSubjectClaim("oid");
        properties.setRolesClaim("roles");
        mapper = new IdentityClaimsMapper(properties);

        Jwt jwt = base()
                .claim("oid", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                .claim("preferred_username", "bob@contoso.com")
                .claim("given_name", "Bob")
                .claim("family_name", "Martin")
                .claim("roles", List.of("integrateur"))
                .build();

        assertThat(mapper.subject(jwt)).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(mapper.email(jwt)).isEqualTo("bob@contoso.com");
        assertThat(mapper.name(jwt)).isEqualTo("Bob Martin");
        assertThat(mapper.rolesFromClaims(jwt))
                .containsExactlyInAnyOrder(SocleRole.INTEGRATEUR, SocleRole.CONTRIBUTEUR);
    }

    @Test
    void okta_groups() {
        properties.setRolesClaim("groups");
        properties.setRoleMapping(Map.of(
                "Socle-Auditeur", SocleRole.AUDITEUR,
                "Socle-Admin", SocleRole.ADMINISTRATEUR_SYSTEME
        ));
        mapper = new IdentityClaimsMapper(properties);

        Jwt jwt = base()
                .claim("sub", "okta|123")
                .claim("email", "c@example.com")
                .claim("groups", List.of("Socle-Auditeur", "Everyone"))
                .build();

        assertThat(mapper.rawRoleValues(jwt)).contains("Socle-Auditeur", "Everyone");
        assertThat(mapper.mapRoles(mapper.rawRoleValues(jwt)))
                .containsExactlyInAnyOrder(SocleRole.AUDITEUR, SocleRole.CONTRIBUTEUR);
    }

    @Test
    void generic_spaceSeparatedRolesString() {
        properties.setRolesClaim("scope");
        mapper = new IdentityClaimsMapper(properties);

        Jwt jwt = base()
                .claim("sub", "x")
                .claim("scope", "openid profile auditeur integrateur")
                .build();

        assertThat(mapper.rawRoleValues(jwt))
                .contains("openid", "profile", "auditeur", "integrateur");
        assertThat(mapper.rolesFromClaims(jwt))
                .contains(SocleRole.AUDITEUR, SocleRole.INTEGRATEUR, SocleRole.CONTRIBUTEUR);
    }

    @Test
    void alwaysIncludesDefaultRole() {
        Jwt jwt = base().claim("sub", "x").claim("realm_access", Map.of("roles", List.of())).build();
        assertThat(mapper.rolesFromClaims(jwt)).containsExactly(SocleRole.CONTRIBUTEUR);
    }

    private static Jwt.Builder base() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer("http://localhost:8081/realms/socle")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
    }
}
