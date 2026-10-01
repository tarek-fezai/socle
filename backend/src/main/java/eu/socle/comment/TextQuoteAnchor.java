// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.comment;

import java.util.UUID;

/**
 * Sélecteur de citation W3C Web Annotation ({@code TextQuoteSelector}).
 * Jamais stocké dans le body TipTap / Git / versions — uniquement en table commentaires.
 */
public record TextQuoteAnchor(
        String exact,
        String prefix,
        String suffix,
        String blockId,
        Integer versionNo
) {
    public static final int CONTEXT_LEN = 32;

    public TextQuoteAnchor {
        exact = exact == null ? null : exact;
        prefix = truncate(prefix, CONTEXT_LEN);
        suffix = truncate(suffix, CONTEXT_LEN);
        blockId = blankToNull(blockId);
    }

    public boolean isEmpty() {
        return exact == null || exact.isBlank();
    }

    public static TextQuoteAnchor of(String exact, String prefix, String suffix, String blockId, Integer versionNo) {
        if (exact == null || exact.isBlank()) {
            return null;
        }
        return new TextQuoteAnchor(exact, prefix, suffix, blockId, versionNo);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
