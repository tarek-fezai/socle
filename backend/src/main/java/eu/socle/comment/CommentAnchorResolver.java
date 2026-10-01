// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.comment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extraction de texte plat depuis un body TipTap JSON, et re-ancrage
 * {@link TextQuoteAnchor} (prefix+exact+suffix, puis exact seul).
 */
public final class CommentAnchorResolver {

    private CommentAnchorResolver() {}

    public record ResolveResult(boolean attached, int startOffset, int endOffset) {
        public static ResolveResult detached() {
            return new ResolveResult(false, -1, -1);
        }

        public static ResolveResult at(int start, int end) {
            return new ResolveResult(true, start, end);
        }
    }

    /** Texte plat du document (ordre de lecture). */
    @SuppressWarnings("unchecked")
    public static String plainText(Object body) {
        if (body == null) {
            return "";
        }
        if (body instanceof String s) {
            return s;
        }
        if (body instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            appendNode((Map<String, Object>) map, sb);
            return sb.toString();
        }
        return String.valueOf(body);
    }

    private static void appendNode(Map<String, Object> node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        String type = String.valueOf(node.getOrDefault("type", ""));
        if ("text".equals(type)) {
            Object t = node.get("text");
            if (t != null) {
                sb.append(t);
            }
            return;
        }
        Object content = node.get("content");
        if (content instanceof List<?> kids) {
            boolean block = isBlock(type);
            for (int i = 0; i < kids.size(); i++) {
                Object kid = kids.get(i);
                if (kid instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> child = (Map<String, Object>) m;
                    appendNode(child, sb);
                }
            }
            if (block && !sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
        }
    }

    private static boolean isBlock(String type) {
        return switch (type) {
            case "paragraph", "heading", "blockquote", "codeBlock", "listItem", "bulletList",
                 "orderedList", "horizontalRule" -> true;
            default -> false;
        };
    }

    /**
     * Re-ancrage : d'abord {@code prefix + exact + suffix}, puis {@code exact} seul.
     * Échec → détaché.
     */
    public static ResolveResult resolve(String documentText, TextQuoteAnchor anchor) {
        if (anchor == null || anchor.isEmpty() || documentText == null) {
            return ResolveResult.detached();
        }
        String exact = anchor.exact();
        String prefix = anchor.prefix() == null ? "" : anchor.prefix();
        String suffix = anchor.suffix() == null ? "" : anchor.suffix();

        if (!prefix.isEmpty() || !suffix.isEmpty()) {
            String needle = prefix + exact + suffix;
            int idx = documentText.indexOf(needle);
            if (idx >= 0) {
                int start = idx + prefix.length();
                return ResolveResult.at(start, start + exact.length());
            }
        }

        int idx = documentText.indexOf(exact);
        if (idx >= 0) {
            return ResolveResult.at(idx, idx + exact.length());
        }
        return ResolveResult.detached();
    }

    /** Construit une ancre autour de {@code exact} trouvé à {@code offset} dans {@code text}. */
    public static TextQuoteAnchor buildFromOffset(
            String text, int offset, String exact, String blockId, int versionNo
    ) {
        if (text == null || exact == null || exact.isBlank() || offset < 0) {
            return null;
        }
        int end = offset + exact.length();
        if (end > text.length() || !text.regionMatches(offset, exact, 0, exact.length())) {
            // fallback : première occurrence
            offset = text.indexOf(exact);
            if (offset < 0) {
                return null;
            }
            end = offset + exact.length();
        }
        String prefix = offset == 0
                ? ""
                : text.substring(Math.max(0, offset - TextQuoteAnchor.CONTEXT_LEN), offset);
        String suffix = end >= text.length()
                ? ""
                : text.substring(end, Math.min(text.length(), end + TextQuoteAnchor.CONTEXT_LEN));
        return TextQuoteAnchor.of(exact, prefix, suffix, blockId, versionNo);
    }

    public static List<String> extractBlockIds(Object body) {
        List<String> ids = new ArrayList<>();
        collectIds(body, ids);
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static void collectIds(Object node, List<String> out) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> m = (Map<String, Object>) map;
        Object attrs = m.get("attrs");
        if (attrs instanceof Map<?, ?> a) {
            Object id = a.get("id");
            if (id == null) {
                id = a.get("blockId");
            }
            if (id != null && !String.valueOf(id).isBlank()) {
                out.add(String.valueOf(id));
            }
        }
        Object content = m.get("content");
        if (content instanceof List<?> kids) {
            for (Object kid : kids) {
                collectIds(kid, out);
            }
        }
    }
}
