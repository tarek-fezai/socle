// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import eu.socle.identity.PatClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatRestrictionFilterTest {

    private final PatRestrictionFilter filter = new PatRestrictionFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
            "read,GET,/api/v1/documents/x,200,",
            "read,HEAD,/api/v1/documents/x,200,",
            "read,OPTIONS,/api/v1/documents/x,200,",
            "read,POST,/api/v1/spaces,403,pat_scope_insufficient",
            "read,PUT,/api/v1/spaces/x,403,pat_scope_insufficient",
            "read,PATCH,/api/v1/spaces/x,403,pat_scope_insufficient",
            "read,DELETE,/api/v1/spaces/x,403,pat_scope_insufficient",
            "read_write,POST,/api/v1/spaces,200,",
            "read_write,DELETE,/api/v1/spaces/x,200,",
            "read_write,GET,/api/v1/admin/overview,403,pat_not_allowed",
            "read,GET,/api/v1/admin,403,pat_not_allowed",
            "read_write,POST,/api/v1/me/tokens,403,pat_not_allowed",
            "read_write,GET,/api/v1/me/tokens,403,pat_not_allowed",
            "read_write,DELETE,/api/v1/me/tokens/abc,403,pat_not_allowed",
            "read_write,GET,/api/v1/me/export,403,pat_not_allowed",
            "read,GET,/api/v1/%61dmin/overview,403,pat_not_allowed",
            "read,GET,/api/v1/me/tokens;jsessionid=x,403,pat_not_allowed",
            "read,GET,/api/v1/me,200,",
            "read,GET,/api/v1/administration-like,200,"
    })
    void patRules(String scope, String method, String uri, int status, String code) throws Exception {
        authenticateWithPat(scope);
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(status);
        if (code != null) {
            assertThat(chain.getRequest()).isNull();
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(response.getContentAsString()).contains("\"code\":\"" + code + "\"");
        } else {
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Test
    void jwtAuthentication_untouched() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/me/tokens");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    private static void authenticateWithPat(String scope) {
        Jwt jwt = Jwt.withTokenValue("pat:x").header("alg", "none")
                .claim("sub", "s")
                .claim(PatClaims.AUTH_METHOD, PatClaims.AUTH_METHOD_PAT)
                .claim(PatClaims.PAT_ID, UUID.randomUUID().toString())
                .claim(PatClaims.PAT_USER_ID, UUID.randomUUID().toString())
                .claim(PatClaims.PAT_SCOPE, scope)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new PatAuthenticationToken(jwt, List.of(), "s"));
    }
}
