// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitution de variables et détection des nœuds {@code placeholder} dans un body TipTap.
 */
public final class TemplateBodySupport {

    public static final String PLACEHOLDER_TYPE = "placeholder";
    public static final String ATTR_HINT = "hint";

    public static final String VAR_DATE = "date";
    public static final String VAR_AUTEUR = "auteur";
    public static final String VAR_ESPACE = "espace";
    public static final String VAR_TITRE = "titre";

    private static final Pattern VAR = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\}\\}");

    private TemplateBodySupport() {}

    /** Copie profonde Map/List (corps TipTap). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return new LinkedHashMap<>(Map.of("type", "doc", "content", List.of()));
        }
        return (Map<String, Object>) deepCopyNode(body);
    }

    private static Object deepCopyNode(Object node) {
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object child : list) {
                out.add(deepCopyNode(child));
            }
            return out;
        }
        if (!(node instanceof Map<?, ?> raw)) {
            return node;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            m.put(String.valueOf(e.getKey()), deepCopyNode(e.getValue()));
        }
        return m;
    }

    /** Remplace {{date}}, {{auteur}}, {{espace}}, {{titre}} dans les nœuds texte uniquement. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> substituteVariables(
            Map<String, Object> body, Map<String, String> vars
    ) {
        if (body == null || body.isEmpty()) {
            return Map.of("type", "doc", "content", List.of());
        }
        return (Map<String, Object>) deepCopyAndSubstitute(body, vars);
    }

    private static Object deepCopyAndSubstitute(Object node, Map<String, String> vars) {
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object child : list) {
                out.add(deepCopyAndSubstitute(child, vars));
            }
            return out;
        }
        if (!(node instanceof Map<?, ?> raw)) {
            return node;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            m.put(String.valueOf(e.getKey()), e.getValue());
        }
        String type = String.valueOf(m.getOrDefault("type", ""));
        if ("text".equals(type) && m.get("text") instanceof String text) {
            m.put("text", replaceVars(text, vars));
        }
        Object content = m.get("content");
        if (content != null) {
            m.put("content", deepCopyAndSubstitute(content, vars));
        }
        Object marks = m.get("marks");
        if (marks != null) {
            m.put("marks", deepCopyAndSubstitute(marks, vars));
        }
        Object attrs = m.get("attrs");
        if (attrs instanceof Map<?, ?> am) {
            Map<String, Object> attrsCopy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : am.entrySet()) {
                attrsCopy.put(String.valueOf(e.getKey()), e.getValue());
            }
            m.put("attrs", attrsCopy);
        }
        return m;
    }

    static String replaceVars(String text, Map<String, String> vars) {
        Matcher matcher = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = vars.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    replacement != null ? replacement : matcher.group(0)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Alias — indices des zones placeholder restantes. */
    public static List<String> collectPlaceholderHints(Map<String, Object> body) {
        return listPlaceholderHints(body);
    }

    /** Indices ({@code attrs.hint}) de tous les nœuds placeholder restants. */
    public static List<String> listPlaceholderHints(Map<String, Object> body) {
        List<String> hints = new ArrayList<>();
        collectPlaceholders(body, hints);
        return List.copyOf(hints);
    }

    public static boolean hasPlaceholders(Map<String, Object> body) {
        return !listPlaceholderHints(body).isEmpty();
    }

    /** Message 409 pour zones restantes. */
    public static String placeholderConflictMessage(List<String> hints) {
        if (hints == null || hints.isEmpty()) {
            return "Zones à compléter restantes";
        }
        return "Zones à compléter restantes : " + String.join(", ", hints);
    }

    @SuppressWarnings("unchecked")
    private static void collectPlaceholders(Object node, List<String> hints) {
        if (node instanceof List<?> list) {
            for (Object child : list) {
                collectPlaceholders(child, hints);
            }
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) {
            return;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        if (PLACEHOLDER_TYPE.equals(String.valueOf(m.getOrDefault("type", "")))) {
            Object attrs = m.get("attrs");
            String hint = "";
            if (attrs instanceof Map<?, ?> am && am.get(ATTR_HINT) != null) {
                hint = String.valueOf(am.get(ATTR_HINT)).trim();
            }
            hints.add(hint.isEmpty() ? "(sans indice)" : hint);
            return;
        }
        Object content = m.get("content");
        if (content != null) {
            collectPlaceholders(content, hints);
        }
    }
}
