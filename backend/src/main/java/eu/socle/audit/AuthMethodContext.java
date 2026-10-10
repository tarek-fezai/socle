// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.audit;

import eu.socle.identity.PatClaims;
import eu.socle.pat.PatAuthenticationToken;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Méthode d'authentification de la requête courante pour l'audit : {@code authMethod="pat"}
 * + {@code patId} quand l'action est faite via un jeton d'accès personnel. Propagé aux
 * écritures {@code @Async} par {@link #taskDecorator()} (le SecurityContext ne suit pas le thread).
 */
public final class AuthMethodContext {

    public static final String AUTH_METHOD_KEY = "authMethod";
    public static final String PAT_ID_KEY = "patId";

    private static final ThreadLocal<UUID> PROPAGATED_PAT = new ThreadLocal<>();

    private AuthMethodContext() {}

    /** Identifiant du PAT de la requête courante, sinon {@code null}. */
    public static UUID currentPatId() {
        UUID propagated = PROPAGATED_PAT.get();
        if (propagated != null) {
            return propagated;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof PatAuthenticationToken patAuth) {
            return PatClaims.patId(patAuth.getToken()).orElse(null);
        }
        return null;
    }

    static Map<String, Object> enrich(Map<String, Object> metadata) {
        UUID patId = currentPatId();
        if (patId == null) {
            return metadata;
        }
        Map<String, Object> copy = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        copy.put(AUTH_METHOD_KEY, PatClaims.AUTH_METHOD_PAT);
        copy.put(PAT_ID_KEY, patId.toString());
        return copy;
    }

    public static TaskDecorator taskDecorator() {
        return runnable -> {
            UUID patId = currentPatId();
            if (patId == null) {
                return runnable;
            }
            return () -> {
                UUID previous = PROPAGATED_PAT.get();
                PROPAGATED_PAT.set(patId);
                try {
                    runnable.run();
                } finally {
                    if (previous == null) {
                        PROPAGATED_PAT.remove();
                    } else {
                        PROPAGATED_PAT.set(previous);
                    }
                }
            };
        };
    }
}
