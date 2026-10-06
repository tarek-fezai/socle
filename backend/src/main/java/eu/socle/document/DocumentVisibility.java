// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

/**
 * Visibilité d'une page — source de vérité SQL ; OpenFGA matérialise via
 * {@code parent} / {@code inherit_from} / {@code user:*}.
 */
public final class DocumentVisibility {

    public static final String ORGANISATION = "organisation";
    public static final String SPACE = "space";
    public static final String RESTRICTED = "restricted";

    private static final Set<String> ALLOWED = Set.of(ORGANISATION, SPACE, RESTRICTED);

    private DocumentVisibility() {}

    public static String requireValid(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility requis");
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(v)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "visibility doit être organisation, space ou restricted");
        }
        return v;
    }

    public static boolean inheritsFromParent(String visibility) {
        return !RESTRICTED.equals(visibility);
    }

    public static boolean isOrganisationWide(String visibility) {
        return ORGANISATION.equals(visibility);
    }
}
