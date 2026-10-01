// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.comment;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Markdown limité (gras, italique, code, liens http(s)) — assaini côté serveur.
 * Aucun HTML stocké ; balises et schémas dangereux neutralisés.
 */
public final class CommentBodySanitizer {

    public static final int MAX_LENGTH = 10_000;

    private static final Pattern HTML_TAGS = Pattern.compile("(?is)<[^>]*>");
    private static final Pattern JS_SCHEME = Pattern.compile("(?i)\\bjavascript\\s*:");
    private static final Pattern DATA_SCHEME = Pattern.compile("(?i)\\bdata\\s*:");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");

    private CommentBodySanitizer() {}

    public static String sanitize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("body requis");
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        s = HTML_TAGS.matcher(s).replaceAll("");
        s = JS_SCHEME.matcher(s).replaceAll("");
        s = DATA_SCHEME.matcher(s).replaceAll("");
        s = rewriteLinks(s);
        s = s.strip();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("body vide après assainissement");
        }
        if (s.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("body trop long (max " + MAX_LENGTH + ")");
        }
        return s;
    }

    private static String rewriteLinks(String s) {
        var m = MD_LINK.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String label = m.group(1);
            String url = m.group(2).trim();
            String safe;
            if (isSafeHttpUrl(url)) {
                safe = url;
            } else if (isMentionUuid(url)) {
                // Mentions @[Nom](uuid) — conserver pour résolution serveur
                safe = url;
            } else {
                safe = "#";
            }
            m.appendReplacement(out, MatcherQuote("[" + label + "](" + safe + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isMentionUuid(String url) {
        if (url == null) {
            return false;
        }
        try {
            UUID.fromString(url.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isSafeHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** Échappe pour appendReplacement (éviter $ / \\). */
    private static String MatcherQuote(String s) {
        return s.replace("\\", "\\\\").replace("$", "\\$");
    }
}
