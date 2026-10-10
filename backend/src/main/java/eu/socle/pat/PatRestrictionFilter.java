// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.pat;

import eu.socle.identity.PatClaims;
import eu.socle.web.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Restrictions des jetons d'accès personnels, quelle que soit la portée : administration,
 * gestion des jetons et export RGPD → 403 {@code pat_not_allowed} ; portée {@code read} :
 * GET / HEAD / OPTIONS uniquement, sinon 403 {@code pat_scope_insufficient}.
 *
 * <p>Instancié par {@code SecurityConfig} (et non {@code @Component}) pour ne pas être
 * enregistré une seconde fois comme filtre servlet hors de la chaîne Spring Security.
 */
public class PatRestrictionFilter extends OncePerRequestFilter {

    static final List<String> FORBIDDEN_PREFIXES = List.of(
            "/api/v1/admin",
            "/api/v1/me/tokens",
            "/api/v1/me/export");
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Chemin décodé (comme le routage MVC) : {@code %61dmin} ne contourne pas la règle. */
    private final UrlPathHelper pathHelper = new UrlPathHelper();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof PatAuthenticationToken patAuth)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = patAuth.getToken();
        if (isForbiddenPath(pathHelper.getPathWithinApplication(request))) {
            write(response, ApiErrors.PAT_NOT_ALLOWED,
                    "Opération interdite avec un jeton d'accès personnel");
            return;
        }
        String scope = jwt.getClaimAsString(PatClaims.PAT_SCOPE);
        if (!PatScope.READ_WRITE.value().equals(scope) && !READ_METHODS.contains(request.getMethod())) {
            write(response, ApiErrors.PAT_SCOPE_INSUFFICIENT,
                    "Jeton en lecture seule : méthode " + request.getMethod() + " refusée");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isForbiddenPath(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : FORBIDDEN_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static void write(HttpServletResponse response, String code, String detail) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"Forbidden\",\"status\":403,\"detail\":\""
                + detail.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"code\":\"" + code + "\"}");
    }
}
