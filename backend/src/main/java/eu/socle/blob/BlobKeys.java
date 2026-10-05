// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.blob;

import java.util.Set;
import java.util.UUID;

/**
 * Validation des clés blob — UUID uniquement (pièces jointes), plus une liste fermée de clés
 * d'instance fixes ({@code branding/logo}, {@code branding/favicon}) ; pas de traversée de chemin.
 */
public final class BlobKeys {

    /** Logo d'instance (un seul objet, remplacé à chaque upload). */
    public static final String BRANDING_LOGO = "branding/logo";
    /** Favicon d'instance. */
    public static final String BRANDING_FAVICON = "branding/favicon";

    private static final Set<String> RESERVED = Set.of(BRANDING_LOGO, BRANDING_FAVICON);

    private BlobKeys() {}

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * @throws IllegalArgumentException si la clé n'est ni une clé réservée, ni un UUID canonique
     *         (refuse {@code ..}, séparateurs, préfixes, etc.)
     */
    public static String requireValid(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("clé blob vide");
        }
        if (RESERVED.contains(key)) {
            return key;
        }
        if (key.contains("/") || key.contains("\\") || key.contains("..")
                || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("clé blob invalide (traversée refusée)");
        }
        try {
            UUID parsed = UUID.fromString(key);
            String canonical = parsed.toString();
            if (!canonical.equals(key)) {
                throw new IllegalArgumentException("clé blob invalide (UUID non canonique)");
            }
            return canonical;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("clé blob invalide (UUID attendu)", ex);
        }
    }
}
