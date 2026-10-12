// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.team;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

/** Rôle d'un membre dans une équipe — {@code docs/teams-and-access.md} § 1. */
public enum TeamRole {
    proprietaire,
    approbateur,
    contributeur,
    lecteur;

    public static final Set<String> NAMES = Set.of(
            proprietaire.name(), approbateur.name(), contributeur.name(), lecteur.name());

    public static TeamRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rôle d'équipe requis");
        }
        try {
            return valueOf(raw.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "rôle d'équipe invalide (proprietaire|approbateur|contributeur|lecteur)");
        }
    }
}
