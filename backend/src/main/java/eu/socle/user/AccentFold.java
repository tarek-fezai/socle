// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.user;

import java.text.Normalizer;
import java.util.Locale;

/** Normalisation casse + accents pour l'annuaire ({@code docs/teams-and-access.md} § 2.1). */
public final class AccentFold {

    private AccentFold() {}

    public static String fold(String raw) {
        if (raw == null) {
            return "";
        }
        String nfd = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    /** Échappe {@code %} et {@code _} pour un {@code LIKE} SQL. */
    public static String likeContains(String q) {
        String folded = fold(q).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + folded + "%";
    }
}
