// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation TipTap à l'écriture (create / update / restore / draft) :
 * liste blanche de types, marques et attributs ; URLs http(s) ou chemins internes uniquement.
 */
@Component
public class TipTapContentValidator {

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final Set<String> NODE_TYPES = Set.of(
            "doc", "paragraph", "text", "heading", "bulletList", "orderedList", "listItem",
            "codeBlock", "blockquote", "hardBreak", "horizontalRule",
            "placeholder", "attachment", "image", "figure", "figcaption", "transclusion",
            "table", "tableRow", "tableCell", "tableHeader",
            "date", "button", "video");

    private static final Set<String> MARKS = Set.of(
            "bold", "strong", "italic", "em", "strike", "code", "link", "underline");

    private static final Map<String, Set<String>> NODE_ATTRS = Map.ofEntries(
            Map.entry("doc", Set.of()),
            Map.entry("paragraph", Set.of()),
            Map.entry("text", Set.of()),
            Map.entry("heading", Set.of("level")),
            Map.entry("bulletList", Set.of()),
            // TipTap ≥2.27 émet aussi `type` (null / 1 / a / A / i / I).
            Map.entry("orderedList", Set.of("start", "type")),
            Map.entry("listItem", Set.of()),
            Map.entry("codeBlock", Set.of("language")),
            Map.entry("blockquote", Set.of()),
            Map.entry("hardBreak", Set.of()),
            Map.entry("horizontalRule", Set.of()),
            Map.entry("placeholder", Set.of("hint")),
            Map.entry("attachment", Set.of("id", "filename", "mediaType", "sizeBytes")),
            Map.entry("image", Set.of(
                    "id", "src", "alt", "caption", "filename", "mediaType", "sizeBytes", "width", "height")),
            Map.entry("figure", Set.of()),
            Map.entry("figcaption", Set.of()),
            Map.entry("transclusion", Set.of("documentId")),
            Map.entry("table", Set.of()),
            Map.entry("tableRow", Set.of()),
            Map.entry("tableCell", Set.of("colspan", "rowspan", "colwidth")),
            Map.entry("tableHeader", Set.of("colspan", "rowspan", "colwidth")),
            Map.entry("date", Set.of("value")),
            Map.entry("button", Set.of("label", "href", "documentId")),
            Map.entry("video", Set.of("id", "filename", "mediaType", "sizeBytes")));

    private static final Set<String> MARK_ATTRS_LINK = Set.of("href", "target", "rel", "class");

    /** Valide le corps ; lève {@link ApiErrors#contentInvalid} à la première erreur. */
    public void validate(Map<String, Object> body) {
        if (body == null) {
            throw ApiErrors.contentInvalid("$", "body requis");
        }
        Object type = body.get("type");
        if (!"doc".equals(type)) {
            throw ApiErrors.contentInvalid("$", "type « doc » requis");
        }
        validateNode(body, "$");
    }

    /**
     * Première erreur de validation, ou vide si conforme.
     * Utile pour inventaire / journalisation sans interrompre le flux.
     */
    public Optional<String> firstError(Map<String, Object> body) {
        try {
            validate(body);
            return Optional.empty();
        } catch (CodedStatusException ex) {
            String reason = ex.getReason();
            return Optional.of(reason != null && !reason.isBlank() ? reason : ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void validateNode(Map<String, Object> node, String path) {
        Object typeObj = node.get("type");
        if (!(typeObj instanceof String type) || type.isBlank()) {
            throw ApiErrors.contentInvalid(path, "type de nœud manquant");
        }
        if (!NODE_TYPES.contains(type)) {
            throw ApiErrors.contentInvalid(path, "type de nœud non autorisé : " + type);
        }

        Object attrsObj = node.get("attrs");
        if (attrsObj != null) {
            if (!(attrsObj instanceof Map<?, ?> rawAttrs)) {
                throw ApiErrors.contentInvalid(path + ".attrs", "attrs doit être un objet");
            }
            Map<String, Object> attrs = (Map<String, Object>) rawAttrs;
            Set<String> allowed = NODE_ATTRS.getOrDefault(type, Set.of());
            for (String key : attrs.keySet()) {
                if (!allowed.contains(key)) {
                    throw ApiErrors.contentInvalid(path + ".attrs." + key, "attribut non autorisé : " + key);
                }
            }
            validateTypedAttrs(type, attrs, path);
        }

        if ("text".equals(type)) {
            Object marks = node.get("marks");
            if (marks instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object m = list.get(i);
                    if (!(m instanceof Map<?, ?> markMap)) {
                        throw ApiErrors.contentInvalid(path + ".marks[" + i + "]", "marque invalide");
                    }
                    validateMark((Map<String, Object>) markMap, path + ".marks[" + i + "]");
                }
            }
            return;
        }

        Object content = node.get("content");
        if (content == null) {
            return;
        }
        if (!(content instanceof List<?> list)) {
            throw ApiErrors.contentInvalid(path + ".content", "content doit être un tableau");
        }
        for (int i = 0; i < list.size(); i++) {
            Object child = list.get(i);
            if (!(child instanceof Map<?, ?> childMap)) {
                throw ApiErrors.contentInvalid(path + ".content[" + i + "]", "nœud invalide");
            }
            validateNode((Map<String, Object>) childMap, path + ".content[" + i + "]");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateMark(Map<String, Object> mark, String path) {
        Object typeObj = mark.get("type");
        if (!(typeObj instanceof String type) || !MARKS.contains(type)) {
            throw ApiErrors.contentInvalid(path, "marque non autorisée : " + typeObj);
        }
        Object attrsObj = mark.get("attrs");
        if (attrsObj == null) {
            return;
        }
        if (!(attrsObj instanceof Map<?, ?> raw)) {
            throw ApiErrors.contentInvalid(path + ".attrs", "attrs doit être un objet");
        }
        Map<String, Object> attrs = (Map<String, Object>) raw;
        if ("link".equals(type)) {
            for (String key : attrs.keySet()) {
                if (!MARK_ATTRS_LINK.contains(key)) {
                    throw ApiErrors.contentInvalid(path + ".attrs." + key, "attribut non autorisé : " + key);
                }
            }
            Object href = attrs.get("href");
            if (href != null) {
                requireSafeUrl(String.valueOf(href), path + ".attrs.href");
            }
        } else if (!attrs.isEmpty()) {
            throw ApiErrors.contentInvalid(path + ".attrs", "attributs non autorisés pour la marque " + type);
        }
    }

    private void validateTypedAttrs(String type, Map<String, Object> attrs, String path) {
        switch (type) {
            case "date" -> {
                Object v = attrs.get("value");
                if (!(v instanceof String s) || !ISO_DATE.matcher(s).matches()) {
                    throw ApiErrors.contentInvalid(path + ".attrs.value", "date ISO AAAA-MM-JJ requise");
                }
            }
            case "button" -> {
                Object href = attrs.get("href");
                Object docId = attrs.get("documentId");
                if (href != null) {
                    requireSafeUrl(String.valueOf(href), path + ".attrs.href");
                }
                if (docId != null && !(docId instanceof String s && UUID.matcher(s).matches())) {
                    throw ApiErrors.contentInvalid(path + ".attrs.documentId", "UUID document requis");
                }
                if (href == null && docId == null) {
                    throw ApiErrors.contentInvalid(path + ".attrs", "href ou documentId requis");
                }
            }
            case "video", "attachment" -> requireUuidAttr(attrs, "id", path);
            case "image" -> {
                Object id = attrs.get("id");
                Object src = attrs.get("src");
                if (id != null) {
                    requireUuidAttr(attrs, "id", path);
                } else if (src != null) {
                    requireSafeUrl(String.valueOf(src), path + ".attrs.src");
                }
            }
            case "transclusion" -> requireUuidAttr(attrs, "documentId", path);
            case "tableCell", "tableHeader" -> {
                // V1 : pas de fusion (colspan/rowspan > 1) — sinon perte Markdown GFM.
                rejectMerge(attrs, "colspan", path);
                rejectMerge(attrs, "rowspan", path);
            }
            default -> {
            }
        }
    }

    private static void rejectMerge(Map<String, Object> attrs, String key, String path) {
        Object v = attrs.get(key);
        if (v == null) {
            return;
        }
        int n = v instanceof Number num ? num.intValue() : -1;
        if (n > 1) {
            throw ApiErrors.contentInvalid(path + ".attrs." + key, "fusion de cellules non supportée (V1)");
        }
    }

    private static void requireUuidAttr(Map<String, Object> attrs, String key, String path) {
        Object v = attrs.get(key);
        if (!(v instanceof String s) || !UUID.matcher(s).matches()) {
            throw ApiErrors.contentInvalid(path + ".attrs." + key, "UUID requis");
        }
    }

    /**
     * http(s) absolu, ou chemin relatif interne commençant par {@code /} (pas {@code //}).
     * Refuse {@code javascript:}, {@code data:}, {@code vbscript:}, etc.
     */
    static void requireSafeUrl(String raw, String path) {
        if (raw == null || raw.isBlank()) {
            throw ApiErrors.contentInvalid(path, "URL vide");
        }
        String url = raw.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:")
                || lower.startsWith("data:")
                || lower.startsWith("vbscript:")
                || lower.startsWith("file:")) {
            throw ApiErrors.contentInvalid(path, "schéma d'URL interdit");
        }
        if (url.startsWith("/") && !url.startsWith("//")) {
            return;
        }
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return;
        }
        throw ApiErrors.contentInvalid(path, "URL http(s) ou chemin interne requis");
    }
}
