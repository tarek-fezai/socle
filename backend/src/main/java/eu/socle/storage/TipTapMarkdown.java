// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversion TipTap/ProseMirror JSON ↔ Markdown overlay (mode Git).
 *
 * <p><b>Garantie d'absence de perte :</b> pour chaque bloc de premier niveau,
 * {@code toMarkdown} tente une sérialisation Markdown lisible puis reparse.
 * Si le résultat n'est pas strictement égal au bloc d'origine, le bloc part
 * en {@code :::socle-json}. Voir {@code docs/storage-providers.md}.
 */
public final class TipTapMarkdown {

    public static final String TRANSCLUSION_TYPE = "transclusion";
    public static final String ATTACHMENT_TYPE = "attachment";
    public static final String IMAGE_TYPE = "image";
    public static final String ATTR_DOCUMENT_ID = "documentId";
    public static final String ATTR_ATTACHMENT_ID = "id";
    public static final String SOCLE_JSON_FENCE = ":::socle-json";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TRANSCLUSION_LINE = Pattern.compile(
            "^::transclusion\\{documentId=\"([^\"]*)\"\\}\\s*$");
    private static final Pattern ATTACHMENT_LINE = Pattern.compile(
            "^::attachment\\{id=\"([^\"]*)\"\\}\\s*$");
    private static final Pattern IMAGE_ATTACHMENT_LINE = Pattern.compile(
            "^!\\[([^\\]]*)]\\(attachment:([0-9a-fA-F-]{36})\\)\\s*$");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private TipTapMarkdown() {}

    @SuppressWarnings("unchecked")
    public static String toMarkdown(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        Object content = body.get("content");
        if (!(content instanceof List<?> blocks)) {
            Object text = body.get("text");
            return text == null ? "" : text.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> m) {
                appendBlockLossless(sb, (Map<String, Object>) m);
            }
        }
        return sb.toString().stripTrailing() + (sb.isEmpty() ? "" : "\n");
    }

    /**
     * Tente Markdown lisible ; si l'aller-retour n'est pas strictement égal → socle-json.
     */
    static void appendBlockLossless(StringBuilder sb, Map<String, Object> block) {
        StringBuilder candidate = new StringBuilder();
        tryRenderBlock(candidate, block);
        String md = candidate.toString();
        if (md.isBlank()) {
            appendSocleJson(sb, block);
            return;
        }
        Map<String, Object> roundTripped = fromMarkdown(md);
        Object content = roundTripped.get("content");
        if (content instanceof List<?> list
                && list.size() == 1
                && list.getFirst() instanceof Map<?, ?> parsed
                && deepEquals(normalize(parsed), normalize(block))) {
            sb.append(md);
            if (!md.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            appendSocleJson(sb, block);
        }
    }

    /** Rendu Markdown « optimiste » (sans garde). */
    @SuppressWarnings("unchecked")
    static void tryRenderBlock(StringBuilder sb, Map<String, Object> block) {
        String type = String.valueOf(block.getOrDefault("type", ""));
        switch (type) {
            case "heading" -> {
                int level = 2;
                Object attrs = block.get("attrs");
                if (attrs instanceof Map<?, ?> am && am.get("level") instanceof Number n) {
                    level = n.intValue();
                }
                if (attrs instanceof Map<?, ?> am && am.size() > 1) {
                    // attrs hors level → laisser la garde basculer en socle-json
                    sb.append("#".repeat(Math.max(1, Math.min(level, 6)))).append(' ')
                            .append(inlineMarkdown(block)).append("\n\n");
                    return;
                }
                sb.append("#".repeat(Math.max(1, Math.min(level, 6)))).append(' ')
                        .append(inlineMarkdown(block)).append("\n\n");
            }
            case "paragraph" -> {
                if (!block.containsKey("content")
                        || (block.get("content") instanceof List<?> c && c.isEmpty())) {
                    // Paragraphe vide : marqueur dédié
                    sb.append("\\\n\n");
                    return;
                }
                String inline = inlineMarkdown(block);
                sb.append(escapeParagraphMarkdown(inline)).append("\n\n");
            }
            case "bulletList" -> appendList(sb, block, false);
            case "orderedList" -> appendList(sb, block, true);
            case "codeBlock" -> appendCodeBlock(sb, block);
            case "blockquote" -> appendBlockquote(sb, block);
            case TRANSCLUSION_TYPE -> appendTransclusion(sb, block);
            case ATTACHMENT_TYPE -> appendAttachment(sb, block);
            case IMAGE_TYPE -> appendImageAttachment(sb, block);
            default -> appendSocleJson(sb, block);
        }
    }

    public static Map<String, Object> fromMarkdown(String markdown) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            content.add(emptyParagraph());
            return doc(content);
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        List<String> paragraphBuf = new ArrayList<>();
        List<Map<String, Object>>[] listItemsHolder = new List[]{null};
        boolean[] orderedHolder = {false};

        Runnable flushPara = () -> {
            if (paragraphBuf.isEmpty()) {
                return;
            }
            // Marqueur paragraphe vide : une seule ligne "\"
            if (paragraphBuf.size() == 1 && "\\".equals(paragraphBuf.getFirst())) {
                paragraphBuf.clear();
                content.add(emptyParagraph());
                return;
            }
            String text = String.join("\n", paragraphBuf);
            paragraphBuf.clear();
            String unescaped = unescapeParagraphLines(text);
            content.add(paragraphFromInline(unescaped));
        };
        Runnable flushList = () -> {
            if (listItemsHolder[0] != null) {
                content.add(listNode(orderedHolder[0], listItemsHolder[0]));
                listItemsHolder[0] = null;
            }
        };

        while (i < lines.length) {
            String line = lines[i];

            if (line.trim().equals(SOCLE_JSON_FENCE)) {
                flushPara.run();
                flushList.run();
                i++;
                StringBuilder jsonBuf = new StringBuilder();
                while (i < lines.length && !lines[i].trim().equals(":::")) {
                    if (!jsonBuf.isEmpty()) {
                        jsonBuf.append('\n');
                    }
                    jsonBuf.append(lines[i]);
                    i++;
                }
                if (i < lines.length) {
                    i++;
                }
                content.add(parseSocleJson(jsonBuf.toString()));
                continue;
            }

            if (line.startsWith("```")) {
                flushPara.run();
                flushList.run();
                String fence = leadingBackticks(line);
                String lang = line.substring(fence.length()).trim();
                i++;
                StringBuilder code = new StringBuilder();
                while (i < lines.length && !lines[i].startsWith(fence)) {
                    if (!code.isEmpty()) {
                        code.append('\n');
                    }
                    code.append(lines[i]);
                    i++;
                }
                if (i < lines.length) {
                    i++;
                }
                content.add(codeBlock(code.toString(), lang.isEmpty() ? null : lang));
                continue;
            }

            Matcher tx = TRANSCLUSION_LINE.matcher(line);
            if (tx.matches()) {
                flushPara.run();
                flushList.run();
                String id = tx.group(1);
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", TRANSCLUSION_TYPE);
                node.put("attrs", Map.of(ATTR_DOCUMENT_ID, id));
                content.add(node);
                i++;
                continue;
            }

            Matcher att = ATTACHMENT_LINE.matcher(line);
            if (att.matches()) {
                flushPara.run();
                flushList.run();
                String id = att.group(1);
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", ATTACHMENT_TYPE);
                node.put("attrs", Map.of(ATTR_ATTACHMENT_ID, id));
                content.add(node);
                i++;
                continue;
            }

            Matcher img = IMAGE_ATTACHMENT_LINE.matcher(line);
            if (img.matches()) {
                flushPara.run();
                flushList.run();
                String alt = img.group(1);
                String id = img.group(2);
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", IMAGE_TYPE);
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put(ATTR_ATTACHMENT_ID, id);
                attrs.put("alt", alt);
                node.put("attrs", attrs);
                content.add(node);
                i++;
                continue;
            }

            if (line.matches("^#{1,6}\\s+.*") || line.matches("^#{1,6}$")) {
                flushPara.run();
                flushList.run();
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') {
                    level++;
                }
                String text = level < line.length() ? line.substring(level).trim() : "";
                Map<String, Object> heading = new LinkedHashMap<>();
                heading.put("type", "heading");
                heading.put("attrs", Map.of("level", Math.min(level, 6)));
                heading.put("content", parseInline(text));
                content.add(heading);
                i++;
                continue;
            }

            if (line.matches("^[-*]\\s+.*")) {
                flushPara.run();
                if (listItemsHolder[0] == null || orderedHolder[0]) {
                    flushList.run();
                    listItemsHolder[0] = new ArrayList<>();
                    orderedHolder[0] = false;
                }
                listItemsHolder[0].add(listItemFromInline(line.replaceFirst("^[-*]\\s+", "")));
                i++;
                continue;
            }

            if (line.matches("^\\d+\\.\\s+.*")) {
                flushPara.run();
                if (listItemsHolder[0] == null || !orderedHolder[0]) {
                    flushList.run();
                    listItemsHolder[0] = new ArrayList<>();
                    orderedHolder[0] = true;
                }
                listItemsHolder[0].add(listItemFromInline(line.replaceFirst("^\\d+\\.\\s+", "")));
                i++;
                continue;
            }

            if (line.startsWith("> ") || line.equals(">")) {
                flushPara.run();
                flushList.run();
                List<Map<String, Object>> quoteBlocks = new ArrayList<>();
                List<String> paraLines = new ArrayList<>();
                Runnable flushQuotePara = () -> {
                    if (paraLines.isEmpty()) {
                        return;
                    }
                    if (paraLines.size() == 1 && "\\".equals(paraLines.getFirst())) {
                        paraLines.clear();
                        quoteBlocks.add(emptyParagraph());
                        return;
                    }
                    quoteBlocks.add(paragraphFromInline(unescapeParagraphLines(String.join("\n", paraLines))));
                    paraLines.clear();
                };
                while (i < lines.length && (lines[i].startsWith("> ") || lines[i].equals(">"))) {
                    String rest = lines[i].startsWith("> ") ? lines[i].substring(2) : "";
                    if (rest.isEmpty() && lines[i].equals(">")) {
                        flushQuotePara.run();
                    } else {
                        paraLines.add(rest);
                    }
                    i++;
                }
                flushQuotePara.run();
                Map<String, Object> bq = new LinkedHashMap<>();
                bq.put("type", "blockquote");
                bq.put("content", quoteBlocks.isEmpty() ? List.of(emptyParagraph()) : quoteBlocks);
                content.add(bq);
                continue;
            }

            if (line.isBlank()) {
                flushPara.run();
                flushList.run();
                i++;
                continue;
            }

            if (listItemsHolder[0] != null) {
                flushList.run();
            }
            paragraphBuf.add(line);
            i++;
        }
        flushPara.run();
        flushList.run();
        if (content.isEmpty()) {
            content.add(emptyParagraph());
        }
        return doc(content);
    }

    private static void appendTransclusion(StringBuilder sb, Map<String, Object> block) {
        Object attrs = block.get("attrs");
        if (!(attrs instanceof Map<?, ?> am)) {
            appendSocleJson(sb, block);
            return;
        }
        // Uniquement documentId — tout attr supplémentaire → garde → socle-json
        if (am.size() != 1 || !am.containsKey(ATTR_DOCUMENT_ID)) {
            appendSocleJson(sb, block);
            return;
        }
        if (block.containsKey("content") && !isEmptyContent(block.get("content"))) {
            appendSocleJson(sb, block);
            return;
        }
        String id = String.valueOf(am.get(ATTR_DOCUMENT_ID));
        if (!isValidUuid(id)) {
            appendSocleJson(sb, block);
            return;
        }
        sb.append("::transclusion{documentId=\"").append(id).append("\"}\n\n");
    }

    /** Directive fichier : {@code ::attachment{id="…"}} — attrs = {id} uniquement. */
    private static void appendAttachment(StringBuilder sb, Map<String, Object> block) {
        Object attrs = block.get("attrs");
        if (!(attrs instanceof Map<?, ?> am)) {
            appendSocleJson(sb, block);
            return;
        }
        if (am.size() != 1 || !am.containsKey(ATTR_ATTACHMENT_ID)) {
            appendSocleJson(sb, block);
            return;
        }
        if (block.containsKey("content") && !isEmptyContent(block.get("content"))) {
            appendSocleJson(sb, block);
            return;
        }
        String id = String.valueOf(am.get(ATTR_ATTACHMENT_ID));
        if (!isValidUuid(id)) {
            appendSocleJson(sb, block);
            return;
        }
        sb.append("::attachment{id=\"").append(id).append("\"}\n\n");
    }

    /** Image protégée : {@code ![alt](attachment:<id>)} — attrs = {id} ou {id,alt}. */
    private static void appendImageAttachment(StringBuilder sb, Map<String, Object> block) {
        Object attrs = block.get("attrs");
        if (!(attrs instanceof Map<?, ?> am)) {
            appendSocleJson(sb, block);
            return;
        }
        if (!am.containsKey(ATTR_ATTACHMENT_ID)) {
            appendSocleJson(sb, block);
            return;
        }
        for (Object key : am.keySet()) {
            String k = String.valueOf(key);
            if (!ATTR_ATTACHMENT_ID.equals(k) && !"alt".equals(k)) {
                appendSocleJson(sb, block);
                return;
            }
        }
        if (block.containsKey("content") && !isEmptyContent(block.get("content"))) {
            appendSocleJson(sb, block);
            return;
        }
        String id = String.valueOf(am.get(ATTR_ATTACHMENT_ID));
        if (!isValidUuid(id)) {
            appendSocleJson(sb, block);
            return;
        }
        String alt = am.containsKey("alt") ? String.valueOf(am.get("alt")) : "";
        if (alt.contains("]") || alt.contains("\n")) {
            appendSocleJson(sb, block);
            return;
        }
        sb.append("![").append(alt).append("](attachment:").append(id).append(")\n\n");
    }

    @SuppressWarnings("unchecked")
    private static void appendList(StringBuilder sb, Map<String, Object> block, boolean ordered) {
        Object content = block.get("content");
        if (!(content instanceof List<?> items)) {
            return;
        }
        // Listes avec structure riche (imbrication, multi-para, tx) → garde basculera
        int i = 1;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> im)) {
                continue;
            }
            Map<String, Object> itemMap = (Map<String, Object>) im;
            String prefix = ordered ? (i++ + ". ") : "- ";
            // Item simple = un seul paragraph inline
            Object ic = itemMap.get("content");
            if (ic instanceof List<?> children && children.size() == 1
                    && children.getFirst() instanceof Map<?, ?> only
                    && "paragraph".equals(only.get("type"))) {
                sb.append(prefix).append(inlineMarkdown((Map<String, Object>) only).trim()).append('\n');
            } else {
                // Forcer échec de round-trip → socle-json via contenu non simple
                sb.append(prefix).append(inlineMarkdown(itemMap).trim()).append('\n');
            }
        }
        sb.append('\n');
    }

    @SuppressWarnings("unchecked")
    private static void appendBlockquote(StringBuilder sb, Map<String, Object> block) {
        Object content = block.get("content");
        if (!(content instanceof List<?> children)) {
            sb.append("> \n\n");
            return;
        }
        for (int c = 0; c < children.size(); c++) {
            if (c > 0) {
                sb.append(">\n");
            }
            Object child = children.get(c);
            if (child instanceof Map<?, ?> cm && "paragraph".equals(cm.get("type"))) {
                Map<String, Object> para = (Map<String, Object>) cm;
                if (!para.containsKey("content")
                        || (para.get("content") instanceof List<?> l && l.isEmpty())) {
                    sb.append("> \\\n");
                } else {
                    String inline = escapeParagraphMarkdown(inlineMarkdown(para));
                    for (String line : inline.split("\n", -1)) {
                        sb.append("> ").append(line).append('\n');
                    }
                }
            } else if (child instanceof Map<?, ?> cm) {
                // Non-paragraph dans blockquote → rendu plat (garde → socle-json)
                sb.append("> ").append(inlineMarkdown((Map<String, Object>) cm)).append('\n');
            }
        }
        sb.append('\n');
    }

    private static void appendCodeBlock(StringBuilder sb, Map<String, Object> block) {
        String text = inlinePlainText(block);
        String lang = null;
        Object attrs = block.get("attrs");
        if (attrs instanceof Map<?, ?> am && am.get("language") != null) {
            lang = String.valueOf(am.get("language"));
            if (am.size() > 1) {
                // attrs hors language → socle-json via garde
            }
        } else if (attrs instanceof Map<?, ?> am && !am.isEmpty()) {
            // attrs sans language standard
        }
        int fenceLen = Math.max(3, longestBacktickRun(text) + 1);
        String fence = "`".repeat(fenceLen);
        sb.append(fence);
        if (lang != null && !lang.isBlank()) {
            sb.append(lang.trim());
        }
        sb.append('\n').append(text).append('\n').append(fence).append("\n\n");
    }

    private static int longestBacktickRun(String text) {
        int max = 0;
        int cur = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                cur++;
                max = Math.max(max, cur);
            } else {
                cur = 0;
            }
        }
        return max;
    }

    private static String leadingBackticks(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '`') {
            n++;
        }
        return "`".repeat(Math.max(3, n));
    }

    private static boolean isEmptyContent(Object content) {
        return content == null || (content instanceof List<?> list && list.isEmpty());
    }

    private static void appendSocleJson(StringBuilder sb, Map<String, Object> block) {
        try {
            sb.append(SOCLE_JSON_FENCE).append('\n')
                    .append(MAPPER.writeValueAsString(block)).append('\n')
                    .append(":::\n\n");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Impossible de sérialiser le nœud TipTap", e);
        }
    }

    static String escapeParagraphMarkdown(String inline) {
        if (inline == null || inline.isEmpty()) {
            return "";
        }
        String[] lines = inline.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (TRANSCLUSION_LINE.matcher(line).matches()
                    || line.trim().equals(SOCLE_JSON_FENCE)
                    || line.trim().equals(":::")
                    || line.startsWith("```")
                    || line.startsWith("::")
                    || line.matches("^#{1,6}(\\s+.*)?$")
                    || line.matches("^[-*]\\s+.*")
                    || line.matches("^\\d+\\.\\s+.*")
                    || line.startsWith("> ")
                    || line.equals(">")
                    || line.equals("\\")) {
                sb.append('\\').append(line);
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    static String unescapeParagraphLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (line.startsWith("\\") && line.length() > 1
                    && (line.charAt(1) == ':' || line.charAt(1) == '`' || line.charAt(1) == '#'
                    || line.charAt(1) == '-' || line.charAt(1) == '*' || line.charAt(1) == '>'
                    || Character.isDigit(line.charAt(1)) || line.charAt(1) == '\\')) {
                sb.append(line.substring(1));
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String inlineMarkdown(Map<String, Object> node) {
        Object content = node.get("content");
        if (!(content instanceof List<?> parts)) {
            Object text = node.get("text");
            return text == null ? "" : applyMarks(escapeInlineLiterals(text.toString()), node);
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> pm)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) pm;
            String type = String.valueOf(m.get("type"));
            if ("text".equals(type)) {
                String raw = String.valueOf(m.getOrDefault("text", ""));
                // Marks inconnus → ne pas émettre en MD (garde sur bloc parent)
                if (hasUnsupportedMarks(m)) {
                    sb.append('\u0000'); // forcer inégalité si rendu partiel
                }
                sb.append(applyMarks(escapeInlineLiterals(raw), m));
            } else if ("hardBreak".equals(type)) {
                sb.append("\\\n");
            } else {
                // Inline inconnu → signal pour forcer socle-json au niveau bloc
                sb.append('\u0000');
            }
        }
        return sb.toString();
    }

    private static boolean hasUnsupportedMarks(Map<String, Object> node) {
        Object marks = node.get("marks");
        if (!(marks instanceof List<?> list)) {
            return false;
        }
        for (Object mark : list) {
            if (!(mark instanceof Map<?, ?> mm)) {
                return true;
            }
            String type = String.valueOf(mm.get("type"));
            if (!List.of("bold", "strong", "italic", "em", "code", "strike").contains(type)) {
                return true;
            }
            if (mm.size() > 1) {
                // attrs sur mark
                return true;
            }
        }
        return false;
    }

    private static String escapeInlineLiterals(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '`' || c == '~' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String inlinePlainText(Map<String, Object> node) {
        Object content = node.get("content");
        if (!(content instanceof List<?> parts)) {
            Object text = node.get("text");
            return text == null ? "" : text.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> pm && "text".equals(pm.get("type"))) {
                Object t = pm.get("text");
                sb.append(t == null ? "" : t.toString());
            } else if (part instanceof Map<?, ?> pm && "hardBreak".equals(pm.get("type"))) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String applyMarks(String text, Map<String, Object> node) {
        Object marks = node.get("marks");
        if (!(marks instanceof List<?> list) || list.isEmpty()) {
            return text;
        }
        boolean bold = false;
        boolean italic = false;
        boolean code = false;
        boolean strike = false;
        for (Object mark : list) {
            if (!(mark instanceof Map<?, ?> mm)) {
                continue;
            }
            String type = String.valueOf(mm.get("type"));
            switch (type) {
                case "bold", "strong" -> bold = true;
                case "italic", "em" -> italic = true;
                case "code" -> code = true;
                case "strike" -> strike = true;
                default -> { }
            }
        }
        String out = text;
        if (code) {
            out = "`" + out + "`";
        }
        if (strike) {
            out = "~~" + out + "~~";
        }
        if (bold && italic) {
            out = "***" + out + "***";
        } else if (bold) {
            out = "**" + out + "**";
        } else if (italic) {
            out = "*" + out + "*";
        }
        return out;
    }

    static List<Map<String, Object>> parseInline(String text) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (text == null) {
            return parts;
        }
        // hardBreak : lignes jointes avec \\\n déjà split au niveau paragraphe ;
        // ici on gère \\\n restant dans une seule chaîne
        String[] segments = text.split("(?<!\\\\)\\\\\\n", -1);
        for (int s = 0; s < segments.length; s++) {
            if (s > 0) {
                Map<String, Object> br = new LinkedHashMap<>();
                br.put("type", "hardBreak");
                parts.add(br);
            }
            parts.addAll(parseInlineSegment(unescapeInlineLiterals(segments[s])));
        }
        return parts;
    }

    private static String unescapeInlineLiterals(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                char n = text.charAt(i + 1);
                if (n == '*' || n == '`' || n == '~' || n == '\\') {
                    sb.append(n);
                    i++;
                    continue;
                }
            }
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    private static List<Map<String, Object>> parseInlineSegment(String text) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (text.isEmpty()) {
            return parts;
        }
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("***", i)) {
                int end = text.indexOf("***", i + 3);
                if (end > i + 3) {
                    parts.add(markedText(text.substring(i + 3, end), "bold", "italic"));
                    i = end + 3;
                    continue;
                }
            }
            if (text.startsWith("~~", i)) {
                int end = text.indexOf("~~", i + 2);
                if (end > i + 2) {
                    parts.add(markedText(text.substring(i + 2, end), "strike"));
                    i = end + 2;
                    continue;
                }
            }
            if (text.startsWith("**", i)) {
                int end = text.indexOf("**", i + 2);
                if (end > i + 2) {
                    parts.add(markedText(text.substring(i + 2, end), "bold"));
                    i = end + 2;
                    continue;
                }
            }
            if (text.startsWith("`", i)) {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    parts.add(markedText(text.substring(i + 1, end), "code"));
                    i = end + 1;
                    continue;
                }
            }
            if (text.charAt(i) == '*' && !text.startsWith("**", i)) {
                int end = text.indexOf('*', i + 1);
                if (end > i + 1) {
                    parts.add(markedText(text.substring(i + 1, end), "italic"));
                    i = end + 1;
                    continue;
                }
            }
            int next = nextMark(text, i + 1);
            parts.add(plainText(text.substring(i, next)));
            i = next;
        }
        return parts;
    }

    private static int nextMark(String text, int from) {
        int best = text.length();
        for (String m : List.of("***", "~~", "**", "`", "*")) {
            int idx = text.indexOf(m, from);
            if (idx >= 0 && idx < best) {
                best = idx;
            }
        }
        return best;
    }

    private static Map<String, Object> markedText(String text, String... markTypes) {
        Map<String, Object> t = plainText(text);
        List<Map<String, Object>> marks = new ArrayList<>();
        for (String m : markTypes) {
            marks.add(Map.of("type", m));
        }
        t.put("marks", marks);
        return t;
    }

    private static Map<String, Object> plainText(String text) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "text");
        t.put("text", text);
        return t;
    }

    private static Map<String, Object> paragraphFromInline(String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "paragraph");
        // hardBreak via \\\n
        if (text.contains("\\\n")) {
            p.put("content", parseInline(text));
        } else {
            List<Map<String, Object>> inline = parseInline(text);
            p.put("content", inline);
        }
        return p;
    }

    private static Map<String, Object> emptyParagraph() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "paragraph");
        return p;
    }

    private static Map<String, Object> codeBlock(String text, String language) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "codeBlock");
        if (language != null && !language.isBlank()) {
            n.put("attrs", Map.of("language", language));
        }
        n.put("content", List.of(plainText(text)));
        return n;
    }

    private static Map<String, Object> listItemFromInline(String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "listItem");
        item.put("content", List.of(paragraphFromInline(text)));
        return item;
    }

    private static Map<String, Object> listNode(boolean ordered, List<Map<String, Object>> items) {
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("type", ordered ? "orderedList" : "bulletList");
        list.put("content", List.copyOf(items));
        return list;
    }

    private static Map<String, Object> doc(List<Map<String, Object>> content) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "doc");
        d.put("content", content);
        return d;
    }

    private static Map<String, Object> parseSocleJson(String json) {
        try {
            Map<String, Object> node = MAPPER.readValue(json, MAP_TYPE);
            if (node == null || node.isEmpty()) {
                return emptyParagraph();
            }
            return node;
        } catch (JsonProcessingException e) {
            return paragraphFromInline(json);
        }
    }

    private static boolean isValidUuid(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Comparaison profonde TipTap (normalize Number → int). */
    public static boolean deepEquals(Object a, Object b) {
        return Objects.equals(normalize(a), normalize(b));
    }

    @SuppressWarnings("unchecked")
    public static Object normalize(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        if (o instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object e : list) {
                out.add(normalize(e));
            }
            return out;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        return o;
    }
}
