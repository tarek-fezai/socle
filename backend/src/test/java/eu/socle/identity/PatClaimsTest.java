// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatClaimsTest {

    @Test
    void isPat_requiresAuthMethodAndPatTokenValuePrefix() {
        assertThat(PatClaims.isPat(jwt("pat:" + UUID.randomUUID(), true))).isTrue();
        assertThat(PatClaims.isPat(jwt("eyJhbGciOiJSUzI1NiJ9.spoof.sig", true))).isFalse();
        assertThat(PatClaims.isPat(jwt("pat:x", false))).isFalse();
        assertThat(PatClaims.isPat(null)).isFalse();
    }

    @Test
    void spoofedIdpJwt_withSocleClaims_isNotPat() {
        Jwt spoof = Jwt.withTokenValue("eyJhbGciOiJSUzI1NiJ9.aaa.bbb")
                .header("alg", "RS256")
                .claim("sub", "victim")
                .claim(PatClaims.AUTH_METHOD, PatClaims.AUTH_METHOD_PAT)
                .claim(PatClaims.PAT_USER_ID, UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        assertThat(PatClaims.isPat(spoof)).isFalse();
        assertThat(PatClaims.userId(spoof)).isEmpty();
        assertThat(PatClaims.patId(spoof)).isEmpty();
    }

    private static Jwt jwt(String tokenValue, boolean patMethod) {
        Jwt.Builder b = Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .claim("sub", "s")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (patMethod) {
            b.claim(PatClaims.AUTH_METHOD, PatClaims.AUTH_METHOD_PAT)
                    .claim(PatClaims.PAT_ID, UUID.randomUUID().toString())
                    .claim(PatClaims.PAT_USER_ID, UUID.randomUUID().toString());
        }
        return b.build();
    }
}
