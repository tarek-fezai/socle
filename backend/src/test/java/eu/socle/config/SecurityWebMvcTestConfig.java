// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Map;

/**
 * {@link JwtDecoder} factice pour {@code @WebMvcTest} + {@link SecurityConfig}
 * (évite la découverte OIDC / JWK contre Keycloak).
 */
@TestConfiguration
public class SecurityWebMvcTestConfig {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token)
                .headers(h -> h.put("alg", "none"))
                .claims(c -> c.putAll(Map.of("sub", "test-subject")))
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
    }
}
