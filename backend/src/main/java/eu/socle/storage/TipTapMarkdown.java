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
    public static final String DATE_TYPE = "date";
    public static final String BUTTON_TYPE = "button";
    public static final String VIDEO_TYPE = "video";
    public static final String CHART_TYPE = "chart";
    public static final String ATTR_DOCUMENT_ID = "documentId";
    public static final String ATTR_ATTACHMENT_ID = "id";
    public static final String SOCLE_JSON_FENCE = ":::socle-json";
    public static final String CHART_FENCE = ":::chart";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TRANSCLUSION_LINE = Pattern.compile(
            "^::transclusion\\{documentId=\"([^\"]*)\"\\}\\s*$");
    private static final Pattern ATTACHMENT_LINE = Pattern.compile(
            "^::attachment\\{id=\"([^\"]*)\"\\}\\s*$");
    private static final Pattern IMAGE_ATTACHMENT_LINE = Pattern.compile(
            "^!\\[([^\\]]*)]\\(attachment:([0-9a-fA-F-]{36})\\)\\s*$");
    private static final Pattern DATE_LINE = Pattern.compile(
            "^::date\\{value=\"(\\d{4}-\\d{2}-\\d{2})\"\\}\\s*$");
    private static final Pattern VIDEO_LINE = Pattern.compile(
            "^::video\\{id=\"([^\"]*)\"\\}\\s*$");
    private static final Pattern BUTTON_LINE = Pattern.compile(
            "^::button\\{(.*)\\}\\s*$");
    private static final Pattern BUTTON_ATTR = Pattern.compile(
            "(label|href|documentId)=\"([^\"]*)\"");
    private static final Pattern GFM_TABLE_SEP = Pattern.compile(
            "^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
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
            case "paragraph" -> appendParagraphOrDate(sb, block);
            case "bulletList" -> appendList(sb, block, false);
            case "orderedList" -> appendList(sb, block, true);
            case "codeBlock" -> appendCodeBlock(sb, block);
            case "blockquote" -> appendBlockquote(sb, block);
            case TRANSCLUSION_TYPE -> appendTransclusion(sb, block);
            case ATTACHMENT_TYPE -> appendAttachment(sb, block);
            case IMAGE_TYPE -> appendImageAttachment(sb, block);
            case "table" -> appendGfmTable(sb, block);
            case DATE_TYPE -> appendDate(sb, block);
            case BUTTON_TYPE -> appendButton(sb, block);
            case VIDEO_TYPE -> appendVideo(sb, block);
            case CHART_TYPE -> appendChart(sb, block);
            default -> appendSocleJson(sb, block);
        }
    }

    /** {@code :::chart} + JSON TipTap + {@code :::} — aller-retour sans perte. */
    private static void appendChart(StringBuilder sb, Map<String, Object> block) {
        try {
            sb.append(CHART_FENCE).append('\n')
                    .append(MAPPER.writeValueAsString(block)).append('\n')
                    .append(":::\n\n");
        } catch (JsonProcessingException e) {
            appendSocleJson(sb, block);
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

            if (line.trim().equals(SOCLE_JSON_FENCE) || line.trim().equals(CHART_FENCE)) {
                flushPara.run();
                flushList.run();
                boolean chartFence = line.trim().equals(CHART_FENCE);
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
                Map<String, Object> parsed = parseSocleJson(jsonBuf.toString());
                if (chartFence && !CHART_TYPE.equals(parsed.get("type"))) {
                    parsed.put("type", CHART_TYPE);
                }
                content.add(parsed);
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

            Matcher dateM = DATE_LINE.matcher(line);
            if (dateM.matches()) {
                flushPara.run();
                flushList.run();
                Map<String, Object> dateNode = new LinkedHashMap<>();
                dateNode.put("type", DATE_TYPE);
                dateNode.put("attrs", Map.of("value", dateM.group(1)));
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("type", "paragraph");
                wrap.put("content", List.of(dateNode));
                content.add(wrap);
                i++;
                continue;
            }

            Matcher videoM = VIDEO_LINE.matcher(line);
            if (videoM.matches()) {
                flushPara.run();
                flushList.run();
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("type", VIDEO_TYPE);
                node.put("attrs", Map.of(ATTR_ATTACHMENT_ID, videoM.group(1)));
                content.add(node);
                i++;
                continue;
            }

            Matcher buttonM = BUTTON_LINE.matcher(line);
            if (buttonM.matches()) {
                flushPara.run();
                flushList.run();
                Map<String, Object> attrs = parseButtonAttrs(buttonM.group(1));
                if (attrs != null) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("type", BUTTON_TYPE);
                    node.put("attrs", attrs);
                    content.add(node);
                }
                i++;
                continue;
            }

            if (looksLikeGfmTable(lines, i)) {
                flushPara.run();
                flushList.run();
                int consumed = parseGfmTable(lines, i, content);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
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

    /** Paragraphe, ou {@code ::date{value="…"}} si le seul enfant est un nœud date. */
    @SuppressWarnings("unchecked")
    private static void appendParagraphOrDate(StringBuilder sb, Map<String, Object> block) {
        Object content = block.get("content");
        if (!(content instanceof List<?> c) || c.isEmpty()) {
            sb.append("\\\n\n");
            return;
        }
        if (c.size() == 1 && c.getFirst() instanceof Map<?, ?> only
                && DATE_TYPE.equals(String.valueOf(only.get("type")))) {
            appendDate(sb, (Map<String, Object>) only);
            return;
        }
        for (Object o : c) {
            if (o instanceof Map<?, ?> n) {
                String t = String.valueOf(n.get("type"));
                if (!"text".equals(t) && !"hardBreak".equals(t)) {
                    // date/bouton/etc. mélangés → laisser vide → garde socle-json
                    return;
                }
            }
        }
        String inline = inlineMarkdown(block);
        sb.append(escapeParagraphMarkdown(inline)).append("\n\n");
    }

    /** {@code ::date{value="AAAA-MM-JJ"}} — attrs = {value} uniquement. */
    private static void appendDate(StringBuilder sb, Map<String, Object> block) {
        Object attrs = block.get("attrs");
        if (!(attrs instanceof Map<?, ?> am) || am.size() != 1 || !am.containsKey("value")) {
            return;
        }
        String value = String.valueOf(am.get("value"));
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return;
        }
        if (block.containsKey("content") && !isEmptyContent(block.get("content"))) {
            return;
        }
        sb.append("::date{value=\"").append(value).append("\"}\n\n");
    }

    /** {@code ::video{id="…"}} — attrs = {id} uniquement. */
    private static void appendVideo(StringBuilder sb, Map<String, Object> block) {
        Object attrs = block.get("attrs");
        if (!(attrs instanceof Map<?, ?> am) || am.size() != 1 || !am.containsKey(ATTR_ATTACHMENT_ID)) {
            return;
        }
        if (block.containsKey("content") && !isEmptyContent(block.get("content"))) {
            return;
        }
        String id = String.valueOf(am.get(ATTR_ATTACHMENT_ID));
        if (!isValidUuid(id)) {
            return;
        }
        sb.append("::video{id=\"").append(id).append("\"}\n\n");
    }

    /**
     * {@code ::button{label="…" href="…"}} ou {@code documentId="…"}.
     * Attrs autorisés uniquement : label + (href | documentId).
     */
    private static void appendButton(StringBuilder sb, Map<String, Object> block) {
        Object attrs = block.get("attrs");
        if (!(attrs instanceof Map<?, ?> am)) {
            return;
        }
        if (block.containsKey("content") && !isEmptyContent(block.get("content"))) {
            return;
        }
        Object label = am.get("label");
        Object href = am.get("href");
        Object docId = am.get("documentId");
        if (!(label instanceof String labelStr) || labelStr.isBlank()) {
            return;
        }
        if (labelStr.contains("\"") || labelStr.contains("\n")) {
            return;
        }
        for (Object key : am.keySet()) {
            String k = String.valueOf(key);
            if (!"label".equals(k) && !"href".equals(k) && !"documentId".equals(k)) {
                return;
            }
        }
        StringBuilder dir = new StringBuilder("::button{label=\"").append(labelStr).append('"');
        if (href instanceof String hs && !hs.isBlank()) {
            if (hs.contains("\"") || hs.contains("\n")) {
                return;
            }
            dir.append(" href=\"").append(hs).append('"');
        } else if (docId instanceof String ds && isValidUuid(ds)) {
            dir.append(" documentId=\"").append(ds).append('"');
        } else {
            return;
        }
        sb.append(dir).append("}\n\n");
    }

    /**
     * Tableau GFM simple (en-tête + séparateur + lignes, cellules texte sans fusion).
     * Sinon laisse vide → garde {@code :::socle-json}.
     */
    @SuppressWarnings("unchecked")
    private static void appendGfmTable(StringBuilder sb, Map<String, Object> block) {
        Object content = block.get("content");
        if (!(content instanceof List<?> rows) || rows.size() < 2) {
            return;
        }
        List<List<String>> grid = new ArrayList<>();
        int cols = -1;
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row) || !"tableRow".equals(String.valueOf(row.get("type")))) {
                return;
            }
            Object cellsObj = row.get("content");
            if (!(cellsObj instanceof List<?> cells) || cells.isEmpty()) {
                return;
            }
            if (cols < 0) {
                cols = cells.size();
            } else if (cells.size() != cols) {
                return;
            }
            List<String> line = new ArrayList<>();
            for (Object cellObj : cells) {
                if (!(cellObj instanceof Map<?, ?> cell)) {
                    return;
                }
                String ct = String.valueOf(cell.get("type"));
                if (!"tableCell".equals(ct) && !"tableHeader".equals(ct)) {
                    return;
                }
                Object attrs = cell.get("attrs");
                if (attrs instanceof Map<?, ?> am) {
                    Object cs = am.get("colspan");
                    Object rs = am.get("rowspan");
                    if (cs instanceof Number n && n.intValue() > 1) {
                        return;
                    }
                    if (rs instanceof Number n && n.intValue() > 1) {
                        return;
                    }
                }
                String plain = tableCellPlainText((Map<String, Object>) cell);
                if ("\u0000".equals(plain)) {
                    return;
                }
                line.add(plain);
            }
            grid.add(line);
        }
        // Première ligne = en-tête
        sb.append('|');
        for (String h : grid.getFirst()) {
            sb.append(' ').append(escapeTableCell(h)).append(" |");
        }
        sb.append('\n');
        sb.append('|');
        for (int i = 0; i < cols; i++) {
            sb.append(" --- |");
        }
        sb.append('\n');
        for (int r = 1; r < grid.size(); r++) {
            sb.append('|');
            for (String cell : grid.get(r)) {
                sb.append(' ').append(escapeTableCell(cell)).append(" |");
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    @SuppressWarnings("unchecked")
    private static String tableCellPlainText(Map<String, Object> cell) {
        Object content = cell.get("content");
        if (!(content instanceof List<?> blocks) || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object b : blocks) {
            if (!(b instanceof Map<?, ?> para) || !"paragraph".equals(String.valueOf(para.get("type")))) {
                return "\u0000"; // signal non-simple
            }
            Object inline = para.get("content");
            if (!(inline instanceof List<?> parts)) {
                continue;
            }
            for (Object p : parts) {
                if (!(p instanceof Map<?, ?> t) || !"text".equals(String.valueOf(t.get("type")))) {
                    return "\u0000";
                }
                if (t.containsKey("marks") && t.get("marks") instanceof List<?> m && !m.isEmpty()) {
                    return "\u0000";
                }
                sb.append(String.valueOf(t.get("text")));
            }
            sb.append(' ');
        }
        String s = sb.toString().trim();
        if (s.indexOf('\u0000') >= 0) {
            return "\u0000";
        }
        return s;
    }

    private static String escapeTableCell(String cell) {
        if (cell == null || "\u0000".equals(cell)) {
            return "";
        }
        return cell.replace("|", "\\|").replace("\n", " ");
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

    private static Map<String, Object> parseButtonAttrs(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        Matcher m = BUTTON_ATTR.matcher(raw);
        while (m.find()) {
            attrs.put(m.group(1), m.group(2));
        }
        if (!attrs.containsKey("label")) {
            return null;
        }
        if (!attrs.containsKey("href") && !attrs.containsKey("documentId")) {
            return null;
        }
        if (attrs.containsKey("href") && attrs.containsKey("documentId")) {
            // Un seul cible — garder href
            attrs.remove("documentId");
        }
        return attrs;
    }

    private static boolean looksLikeGfmTable(String[] lines, int i) {
        if (i + 1 >= lines.length) {
            return false;
        }
        String header = lines[i].trim();
        String sep = lines[i + 1].trim();
        return header.contains("|") && GFM_TABLE_SEP.matcher(sep).matches();
    }

    /** @return nombre de lignes consommées, ou 0 si échec */
    private static int parseGfmTable(String[] lines, int start, List<Map<String, Object>> content) {
        if (!looksLikeGfmTable(lines, start)) {
            return 0;
        }
        List<String> headerCells = splitTableRow(lines[start]);
        if (headerCells.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(tableRow(headerCells, true));
        int i = start + 2;
        while (i < lines.length && lines[i].contains("|") && !lines[i].isBlank()) {
            if (GFM_TABLE_SEP.matcher(lines[i].trim()).matches()) {
                break;
            }
            List<String> cells = splitTableRow(lines[i]);
            if (cells.size() != headerCells.size()) {
                break;
            }
            rows.add(tableRow(cells, false));
            i++;
        }
        if (rows.size() < 2) {
            return 0;
        }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("type", "table");
        table.put("content", rows);
        content.add(table);
        return i - start;
    }

    private static List<String> splitTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String part : trimmed.split("\\|", -1)) {
            cells.add(part.trim().replace("\\|", "|"));
        }
        return cells;
    }

    private static Map<String, Object> tableRow(List<String> cells, boolean header) {
        List<Map<String, Object>> cellNodes = new ArrayList<>();
        for (String text : cells) {
            Map<String, Object> para = new LinkedHashMap<>();
            para.put("type", "paragraph");
            if (text.isEmpty()) {
                para.put("content", List.of());
            } else {
                para.put("content", List.of(plainText(text)));
            }
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("type", header ? "tableHeader" : "tableCell");
            cell.put("content", List.of(para));
            cellNodes.add(cell);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "tableRow");
        row.put("content", cellNodes);
        return row;
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
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                Object nv = normalize(v);
                // Ignorer colspan/rowspan par défaut (=1) et colwidth null — TipTap vs GFM.
                if (("colspan".equals(key) || "rowspan".equals(key))
                        && nv instanceof Number n && n.intValue() == 1) {
                    return;
                }
                if ("colwidth".equals(key) && (nv == null || (nv instanceof List<?> l && l.isEmpty()))) {
                    return;
                }
                if ("attrs".equals(key) && nv instanceof Map<?, ?> am && am.isEmpty()) {
                    return;
                }
                if ("content".equals(key) && nv instanceof List<?> cl && cl.isEmpty()) {
                    return;
                }
                out.put(key, nv);
            });
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
