// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import eu.socle.document.DocumentDtos.CompareHunk;
import eu.socle.document.DocumentDtos.CompareLine;
import eu.socle.document.DocumentDtos.CompareSpan;
import eu.socle.storage.TipTapMarkdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diff ligne à ligne (Myers, java-diff-utils) de deux corps TipTap sérialisés en Markdown
 * ({@link TipTapMarkdown#toMarkdown}) — corps <strong>stockés</strong> : les directives
 * {@code ::transclusion} restent telles quelles, jamais résolues.
 *
 * <p>Compteurs {@code added}/{@code removed} : lignes <em>non vides</em> ajoutées / supprimées
 * (les lignes vides séparent les blocs Markdown ; les compter gonflerait les statistiques).
 * Les lignes vides restent présentes dans les hunks.
 */
public final class MarkdownLineDiff {

    /** Lignes de contexte autour de chaque modification. */
    public static final int CONTEXT_LINES = 3;

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern TOKEN = Pattern.compile("\\s+|[\\p{L}\\p{N}_]+|[^\\s\\p{L}\\p{N}_]");
    private static final int MAX_SPAN_LINE_LENGTH = 4000;
    private static final double MIN_SPAN_SIMILARITY = 0.3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MarkdownLineDiff() {}

    enum Kind { EQ, ADD, DEL }

    /** Une ligne du script d'édition complet. */
    record Op(Kind kind, Integer oldNo, Integer newNo, String text) {}

    /** Résultat : compteurs + hunks. */
    public record Result(int added, int removed, List<CompareHunk> hunks) {}

    /** Lignes Markdown d'un corps TipTap (sans la ligne vide finale). */
    public static List<String> linesOf(Map<String, Object> body) {
        return splitLines(TipTapMarkdown.toMarkdown(body));
    }

    static List<String> splitLines(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return List.of();
        }
        String normalized = markdown.replace("\r\n", "\n");
        String[] parts = normalized.split("\n", -1);
        int n = parts.length;
        if (n > 0 && parts[n - 1].isEmpty()) {
            n--;
        }
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    /** Compteurs seuls (liste des versions) — pas de construction de hunks. */
    public static int[] stats(List<String> before, List<String> after) {
        int added = 0;
        int removed = 0;
        for (Op op : script(before, after)) {
            if (op.kind() == Kind.ADD && !op.text().isBlank()) {
                added++;
            } else if (op.kind() == Kind.DEL && !op.text().isBlank()) {
                removed++;
            }
        }
        return new int[] {added, removed};
    }

    /** Nombre de lignes non vides (version sans prédécesseur : tout est ajouté). */
    public static int nonBlankCount(List<String> lines) {
        int n = 0;
        for (String line : lines) {
            if (!line.isBlank()) {
                n++;
            }
        }
        return n;
    }

    public static Result compare(List<String> before, List<String> after) {
        List<Op> ops = script(before, after);
        int added = 0;
        int removed = 0;
        for (Op op : ops) {
            if (op.kind() == Kind.ADD && !op.text().isBlank()) {
                added++;
            } else if (op.kind() == Kind.DEL && !op.text().isBlank()) {
                removed++;
            }
        }
        return new Result(added, removed, buildHunks(ops));
    }

    /** Script d'édition complet (EQ / DEL / ADD) via Myers. */
    static List<Op> script(List<String> before, List<String> after) {
        Patch<String> patch = DiffUtils.diff(before, after);
        List<Op> ops = new ArrayList<>(Math.max(before.size(), after.size()));
        int oldIdx = 0;
        int newIdx = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int srcPos = delta.getSource().getPosition();
            while (oldIdx < srcPos) {
                ops.add(new Op(Kind.EQ, oldIdx + 1, newIdx + 1, before.get(oldIdx)));
                oldIdx++;
                newIdx++;
            }
            for (String line : delta.getSource().getLines()) {
                ops.add(new Op(Kind.DEL, oldIdx + 1, null, line));
                oldIdx++;
            }
            for (String line : delta.getTarget().getLines()) {
                ops.add(new Op(Kind.ADD, null, newIdx + 1, line));
                newIdx++;
            }
        }
        while (oldIdx < before.size()) {
            ops.add(new Op(Kind.EQ, oldIdx + 1, newIdx + 1, before.get(oldIdx)));
            oldIdx++;
            newIdx++;
        }
        return ops;
    }

    private static List<CompareHunk> buildHunks(List<Op> ops) {
        int n = ops.size();
        // Titre courant avant chaque op (côté « nouveau » : EQ + ADD ; DEL ignorées).
        String[] headingBefore = new String[n + 1];
        String current = "";
        boolean inFence = false;
        for (int i = 0; i < n; i++) {
            headingBefore[i] = current;
            Op op = ops.get(i);
            if (op.kind() == Kind.DEL) {
                continue;
            }
            String line = op.text();
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            String h = headingText(line);
            if (h != null) {
                current = h;
            }
        }
        headingBefore[n] = current;

        // Fenêtres [start, end) = modifications ± contexte, fusionnées si elles se touchent.
        List<int[]> windows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (ops.get(i).kind() == Kind.EQ) {
                continue;
            }
            int start = Math.max(0, i - CONTEXT_LINES);
            int j = i;
            while (j + 1 < n && ops.get(j + 1).kind() != Kind.EQ) {
                j++;
            }
            int end = Math.min(n, j + 1 + CONTEXT_LINES);
            if (!windows.isEmpty() && start <= windows.getLast()[1]) {
                windows.getLast()[1] = Math.max(windows.getLast()[1], end);
            } else {
                windows.add(new int[] {start, end});
            }
            i = j;
        }

        List<CompareHunk> hunks = new ArrayList<>();
        int cursor = 0;
        for (int[] w : windows) {
            if (w[0] > cursor) {
                hunks.add(new CompareHunk(headingBefore[cursor], List.of(), w[0] - cursor));
            }
            hunks.add(new CompareHunk(headingBefore[w[0]], toLines(ops, w[0], w[1]), 0));
            cursor = w[1];
        }
        if (cursor < n) {
            hunks.add(new CompareHunk(headingBefore[cursor], List.of(), n - cursor));
        }
        return List.copyOf(hunks);
    }

    private static List<CompareLine> toLines(List<Op> ops, int from, int to) {
        List<CompareLine> lines = new ArrayList<>(to - from);
        int i = from;
        while (i < to) {
            Op op = ops.get(i);
            if (op.kind() == Kind.EQ) {
                lines.add(new CompareLine("context", op.oldNo(), op.newNo(), op.text(), null));
                i++;
                continue;
            }
            // Bloc modifié : DEL* puis ADD* — appariement ligne à ligne pour les spans.
            int delStart = i;
            while (i < to && ops.get(i).kind() == Kind.DEL) {
                i++;
            }
            int delEnd = i;
            while (i < to && ops.get(i).kind() == Kind.ADD) {
                i++;
            }
            int addEnd = i;
            int delCount = delEnd - delStart;
            int addCount = addEnd - delEnd;
            int pairs = Math.min(delCount, addCount);
            List<List<CompareSpan>> delSpans = new ArrayList<>();
            List<List<CompareSpan>> addSpans = new ArrayList<>();
            for (int p = 0; p < pairs; p++) {
                List<List<CompareSpan>> pair = wordSpans(
                        ops.get(delStart + p).text(), ops.get(delEnd + p).text());
                delSpans.add(pair == null ? null : pair.get(0));
                addSpans.add(pair == null ? null : pair.get(1));
            }
            for (int d = 0; d < delCount; d++) {
                Op delOp = ops.get(delStart + d);
                lines.add(new CompareLine("del", delOp.oldNo(), null, delOp.text(),
                        d < pairs ? delSpans.get(d) : null));
            }
            for (int a = 0; a < addCount; a++) {
                Op addOp = ops.get(delEnd + a);
                lines.add(new CompareLine("add", null, addOp.newNo(), addOp.text(),
                        a < pairs ? addSpans.get(a) : null));
            }
            if (delCount == 0 && addCount == 0) {
                i++; // garde-fou : ne jamais boucler
            }
        }
        return List.copyOf(lines);
    }

    /**
     * Spans mot à mot pour une ligne modifiée (avant, après) ; {@code null} si les lignes sont
     * trop différentes ou trop longues (surlignage non pertinent).
     */
    static List<List<CompareSpan>> wordSpans(String before, String after) {
        if (before.isBlank() || after.isBlank()
                || before.length() > MAX_SPAN_LINE_LENGTH || after.length() > MAX_SPAN_LINE_LENGTH) {
            return null;
        }
        List<String> a = tokenize(before);
        List<String> b = tokenize(after);
        Patch<String> patch = DiffUtils.diff(a, b);
        List<CompareSpan> delSide = new ArrayList<>();
        List<CompareSpan> addSide = new ArrayList<>();
        int ai = 0;
        int bi = 0;
        int equalChars = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int pos = delta.getSource().getPosition();
            StringBuilder eq = new StringBuilder();
            while (ai < pos) {
                eq.append(a.get(ai));
                ai++;
                bi++;
            }
            if (!eq.isEmpty()) {
                equalChars += eq.length();
                pushSpan(delSide, "eq", eq.toString());
                pushSpan(addSide, "eq", eq.toString());
            }
            String removedText = String.join("", delta.getSource().getLines());
            String addedText = String.join("", delta.getTarget().getLines());
            if (!removedText.isEmpty()) {
                pushSpan(delSide, "del", removedText);
            }
            if (!addedText.isEmpty()) {
                pushSpan(addSide, "add", addedText);
            }
            ai += delta.getSource().size();
            bi += delta.getTarget().size();
        }
        StringBuilder tail = new StringBuilder();
        while (ai < a.size()) {
            tail.append(a.get(ai));
            ai++;
            bi++;
        }
        if (!tail.isEmpty()) {
            equalChars += tail.length();
            pushSpan(delSide, "eq", tail.toString());
            pushSpan(addSide, "eq", tail.toString());
        }
        double similarity = (double) equalChars / Math.max(before.length(), after.length());
        if (similarity < MIN_SPAN_SIMILARITY) {
            return null;
        }
        return List.of(List.copyOf(delSide), List.copyOf(addSide));
    }

    private static void pushSpan(List<CompareSpan> spans, String kind, String text) {
        if (!spans.isEmpty() && spans.getLast().kind().equals(kind)) {
            CompareSpan last = spans.removeLast();
            spans.add(new CompareSpan(kind, last.text() + text));
        } else {
            spans.add(new CompareSpan(kind, text));
        }
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /**
     * Texte du titre d'une ligne : Markdown {@code # Titre} ou nœud TipTap {@code heading}
     * sérialisé en {@code :::socle-json} (une ligne JSON). {@code null} si ce n'est pas un titre.
     */
    static String headingText(String line) {
        Matcher m = HEADING.matcher(line);
        if (m.matches()) {
            return m.group(1);
        }
        if (line.startsWith("{") && line.contains("\"heading\"")) {
            try {
                JsonNode node = MAPPER.readTree(line);
                if (node != null && "heading".equals(node.path("type").asText())) {
                    StringBuilder sb = new StringBuilder();
                    collectText(node, sb);
                    return sb.toString().trim();
                }
            } catch (Exception ignored) {
                // pas du JSON valide : ligne ordinaire
            }
        }
        return null;
    }

    private static void collectText(JsonNode node, StringBuilder sb) {
        if (node.has("text")) {
            sb.append(node.get("text").asText());
        }
        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode child : content) {
                collectText(child, sb);
            }
        }
    }
}
