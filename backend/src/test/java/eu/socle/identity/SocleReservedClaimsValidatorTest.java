// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SocleReservedClaimsValidatorTest {

    private final SocleReservedClaimsValidator validator = new SocleReservedClaimsValidator();

    @Test
    void rejectsAnySoclePrefixedClaim() {
        Jwt jwt = Jwt.withTokenValue("eyJ.x.y").header("alg", "RS256")
                .claim("sub", "u")
                .claim("socle_auth_method", "pat")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void acceptsNormalIdpClaims() {
        Jwt jwt = Jwt.withTokenValue("eyJ.x.y").header("alg", "RS256")
                .claim("sub", "u")
                .claim("email", "u@example.com")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }
}
