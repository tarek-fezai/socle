// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seul point autorisé à lire les claims JWT ({@code jwt.getClaim(...)} / {@code realm_access}).
 * Tout le reste du code consomme cette façade.
 */
@Component
public class IdentityClaimsMapper {

    private static final Logger log = LoggerFactory.getLogger(IdentityClaimsMapper.class);

    private final IdentityProperties properties;
    private final Set<String> loggedUnmapped = ConcurrentHashMap.newKeySet();

    public IdentityClaimsMapper(IdentityProperties properties) {
        this.properties = properties;
    }

    public String subject(Jwt jwt) {
        Object raw = jwt.getClaim(properties.getSubjectClaim());
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    public String email(Jwt jwt) {
        String email = asString(jwt.getClaim(properties.getEmailClaim()));
        if (email != null) {
            return email;
        }
        return asString(jwt.getClaim("preferred_username"));
    }

    public String name(Jwt jwt) {
        String name = asString(jwt.getClaim(properties.getNameClaim()));
        if (name != null) {
            return name;
        }
        String given = asString(jwt.getClaim("given_name"));
        String family = asString(jwt.getClaim("family_name"));
        if (given == null && family == null) {
            return null;
        }
        if (given == null) {
            return family;
        }
        if (family == null) {
            return given;
        }
        return given + " " + family;
    }

    public String issuer(Jwt jwt) {
        if (jwt.getIssuer() != null) {
            return jwt.getIssuer().toString();
        }
        return properties.getIssuerUri();
    }

    /** Claim OIDC {@code email_verified} (booléen ou chaîne). */
    public boolean emailVerified(Jwt jwt) {
        Object raw = jwt.getClaim("email_verified");
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw != null) {
            return "true".equalsIgnoreCase(String.valueOf(raw).trim());
        }
        return false;
    }

    /**
     * Valeurs brutes du claim de rôles configuré (liste, chaîne espace-séparée, ou nested map).
     */
    public List<String> rawRoleValues(Jwt jwt) {
        Object node = resolvePath(jwt, properties.getRolesClaim());
        return flattenRoleValues(node);
    }

    /**
     * Valeurs du claim de groupes ({@code access-policy.groups-claim}) — liste, chaîne
     * espace-séparée ou chemin pointé. Vide si absent.
     */
    public List<String> groups(Jwt jwt) {
        Object node = resolvePath(jwt, properties.getAccessPolicy().getGroupsClaim());
        return flattenRoleValues(node);
    }

    /**
     * Mappe les valeurs brutes vers {@link SocleRole} ; inclut toujours {@code defaultRole}.
     * Les valeurs non mappées sont loguées une seule fois en DEBUG.
     */
    public Set<SocleRole> mapRoles(Collection<String> raw) {
        Set<SocleRole> roles = EnumSet.noneOf(SocleRole.class);
        Map<String, SocleRole> mapping = properties.getRoleMapping();
        if (raw != null) {
            for (String value : raw) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String key = value.trim();
                SocleRole mapped = mapping.get(key);
                if (mapped == null) {
                    // tolérance casse
                    mapped = mapping.entrySet().stream()
                            .filter(e -> e.getKey().equalsIgnoreCase(key))
                            .map(Map.Entry::getValue)
                            .findFirst()
                            .orElse(null);
                }
                if (mapped != null) {
                    roles.add(mapped);
                } else if (loggedUnmapped.add(key)) {
                    log.debug("Unmapped IdP role value ignored: {}", key);
                }
            }
        }
        SocleRole defaultRole = properties.getDefaultRole();
        if (defaultRole != null) {
            roles.add(defaultRole);
        }
        return roles;
    }

    public Set<SocleRole> rolesFromClaims(Jwt jwt) {
        return mapRoles(rawRoleValues(jwt));
    }

    private Object resolvePath(Jwt jwt, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = jwt.getClaim(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    private static List<String> flattenRoleValues(Object node) {
        if (node == null) {
            return List.of();
        }
        if (node instanceof Collection<?> col) {
            List<String> out = new ArrayList<>(col.size());
            for (Object o : col) {
                if (o != null) {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
        if (node instanceof String s) {
            if (s.isBlank()) {
                return List.of();
            }
            String[] parts = s.trim().split("\\s+");
            Set<String> uniq = new LinkedHashSet<>();
            for (String p : parts) {
                if (!p.isBlank()) {
                    uniq.add(p.trim());
                }
            }
            return List.copyOf(uniq);
        }
        // single scalar
        String s = String.valueOf(node).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }

    private static String asString(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }
}
