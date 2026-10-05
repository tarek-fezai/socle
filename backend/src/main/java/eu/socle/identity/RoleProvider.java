// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.identity;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;
import java.util.UUID;

/** Résolution des rôles plateforme pour un JWT + utilisateur Socle. */
public interface RoleProvider {

    Set<SocleRole> resolve(Jwt jwt, UUID userId);
}
