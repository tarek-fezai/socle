// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.branding;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Contraste WCAG 2.x (luminance relative sRGB) d'une couleur d'accent sur fond blanc {@code #FFFFFF}.
 * AA texte normal : ratio ≥ 4.5.
 */
public final class ContrastChecker {

    /** Seuil WCAG AA (texte normal). */
    public static final double AA_NORMAL_TEXT = 4.5;

    private static final Pattern HEX6 = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private ContrastChecker() {}

    /** {@code #RRGGBB} uniquement (pas de notation courte ni de nom CSS). */
    public static boolean isValidHex(String color) {
        return color != null && HEX6.matcher(color).matches();
    }

    public static String normalizeHex(String color) {
        return color.toUpperCase(Locale.ROOT);
    }

    /** Luminance relative WCAG d'une couleur {@code #RRGGBB}. */
    public static double relativeLuminance(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
    }

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Ratio de contraste (1..21) de {@code hex} sur blanc. */
    public static double contrastOnWhite(String hex) {
        double l = relativeLuminance(hex);
        double white = 1.0;
        return (white + 0.05) / (l + 0.05);
    }

    public static boolean passesAaOnWhite(String hex) {
        return contrastOnWhite(hex) >= AA_NORMAL_TEXT;
    }
}
