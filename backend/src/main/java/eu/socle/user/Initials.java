// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

/** Initiales d'affichage — jamais un UUID. */
public final class Initials {

    private Initials() {}

    public static String of(String displayName, String stored) {
        if (stored != null && !stored.isBlank()) {
            return stored.trim();
        }
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length == 1) {
            String p = parts[0];
            return p.substring(0, Math.min(2, p.length())).toUpperCase();
        }
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }
}
