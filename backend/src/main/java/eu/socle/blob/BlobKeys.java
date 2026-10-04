// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.blob;

import java.util.UUID;

/** Validation des clés blob — UUID uniquement, pas de traversée de chemin. */
public final class BlobKeys {

    private BlobKeys() {}

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * @throws IllegalArgumentException si la clé n'est pas un UUID canonique
     *         (refuse {@code ..}, séparateurs, préfixes, etc.)
     */
    public static String requireValid(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("clé blob vide");
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
