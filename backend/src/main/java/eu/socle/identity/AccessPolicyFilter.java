// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import eu.socle.pat.PatAuthenticationToken;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Applique la politique d'accès après validation du JWT, sur les requêtes authentifiées
 * {@code /api/**} (y compris {@code /api/v1/me}). Les endpoints publics sont ignorés.
 *
 * <p>Décision mise en cache ≤ 60 s par (issuer, subject) ({@link AccessDecisionCache}).
 * Refus classique : HTTP 403 {@code {"error":"access_denied","reason":"…"}}.
 * Refus licence : HTTP 403 {@code application/problem+json} ({@code code=licence_user_limit}).
 *
 * <p>Instancié par {@link AccessPolicyConfig} (et non {@code @Component}) pour ne pas être
 * enregistré une seconde fois comme filtre servlet hors de la chaîne Spring Security.
 */
public class AccessPolicyFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";
    private static final String PUBLIC_PREFIX = "/api/v1/public/";

    private final AccessPolicyService accessPolicyService;
    private final AccessDecisionCache decisionCache;
    private final AccessAuditService accessAuditService;
    private final IdentityClaimsMapper claimsMapper;

    public AccessPolicyFilter(
            AccessPolicyService accessPolicyService,
            AccessDecisionCache decisionCache,
            AccessAuditService accessAuditService,
            IdentityClaimsMapper claimsMapper
    ) {
        this.accessPolicyService = accessPolicyService;
        this.decisionCache = decisionCache;
        this.accessAuditService = accessAuditService;
        this.claimsMapper = claimsMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String context = request.getContextPath();
        String path = request.getRequestURI();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return !path.startsWith(API_PREFIX) || path.startsWith(PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        Jwt jwt = jwtAuth.getToken();
        if (auth instanceof PatAuthenticationToken || PatClaims.isPat(jwt)) {
            // Jeton d'accès personnel : compte existant et actif vérifiés à l'authentification ;
            // la politique d'admission (domaine, groupe, JIT) ne s'applique qu'aux JWT de l'IdP.
            chain.doFilter(request, response);
            return;
        }
        String subject = claimsMapper.subject(jwt);
        String issuer = claimsMapper.issuer(jwt);
        if (subject == null || issuer == null) {
            // Laissé à UserSyncService (401 subject/issuer manquant).
            chain.doFilter(request, response);
            return;
        }

        // Refus levé pendant le sync JWT (licence, compte désactivé, …) — prioritaire.
        Object stashed = request.getAttribute(IdentityJwtConfig.SYNC_DENIED_REASON_ATTR);
        if (stashed instanceof AccessPolicyDeniedException syncDenied) {
            accessAuditService.recordDenied(issuer, subject, null, syncDenied.getReason());
            writeDenied(response, syncDenied);
            return;
        }
        if (stashed instanceof AccessDeniedReason syncReason) {
            accessAuditService.recordDenied(issuer, subject, null, syncReason);
            writeDenied(response, new AccessPolicyDeniedException(syncReason));
            return;
        }

        AccessPolicyService.Decision decision = decisionCache.get(issuer, subject).orElse(null);
        if (decision == null) {
            decision = accessPolicyService.evaluate(jwt);
            decisionCache.put(issuer, subject, decision);
        }

        if (decision.granted()) {
            chain.doFilter(request, response);
            return;
        }

        accessAuditService.recordDenied(issuer, subject, null, decision.reason());
        writeDenied(response, new AccessPolicyDeniedException(decision.reason()));
    }

    private static void writeDenied(HttpServletResponse response, AccessPolicyDeniedException denied)
            throws IOException {
        AccessDeniedReason reason = denied.getReason();
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (reason == AccessDeniedReason.LICENCE_USER_LIMIT
                || reason == AccessDeniedReason.IDENTITY_CONFLICT) {
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            String code = reason == AccessDeniedReason.IDENTITY_CONFLICT
                    ? ApiErrors.IDENTITY_CONFLICT
                    : ApiErrors.LICENCE_USER_LIMIT;
            String detail = denied.getDetail() != null && !denied.getDetail().isBlank()
                    ? denied.getDetail()
                    : (reason == AccessDeniedReason.IDENTITY_CONFLICT
                            ? "Identité déjà associée à un autre compte : contactez l'administrateur"
                            : "Aucune licence valide installée : contactez l'administrateur");
            String escaped = detail.replace("\\", "\\\\").replace("\"", "\\\"");
            response.getWriter().write(
                    "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"" + escaped
                            + "\",\"code\":\"" + code + "\"}");
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"access_denied\",\"reason\":\"" + reason.code() + "\"}");
    }
}
