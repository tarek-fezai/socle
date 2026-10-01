// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.storage;

import eu.socle.document.TransclusionResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aller-retour TipTap ↔ Markdown sans perte (garde par bloc + StarterKit).
 */
class TipTapMarkdownTest {

    static final UUID TARGET = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void roundTrip_paragraphAndHeading() {
        Map<String, Object> body = doc(
                heading(2, "Titre"),
                para("Corps searchable")
        );
        assertRoundTripEquals(body);
        String md = TipTapMarkdown.toMarkdown(body);
        assertThat(md).contains("## Titre").contains("Corps searchable");
    }

    @Test
    void roundTrip_transclusionBetweenParagraphs() {
        Map<String, Object> body = doc(para("Avant"), transclusion(TARGET), para("Après"));
        assertRoundTripEquals(body);
        assertThat(TipTapMarkdown.toMarkdown(body))
                .contains("::transclusion{documentId=\"" + TARGET + "\"}");
    }

    @Test
    void roundTrip_transclusionFirstAndLast() {
        assertRoundTripEquals(doc(
                transclusion(TARGET),
                para("Milieu"),
                transclusion(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"))
        ));
    }

    @Test
    void roundTrip_unknownNodeWithAttrsAndNestedContent() {
        Map<String, Object> callout = new LinkedHashMap<>();
        callout.put("type", "callout");
        callout.put("attrs", Map.of("tone", "warning"));
        callout.put("content", List.of(para("Attention")));
        Map<String, Object> body = doc(callout);
        assertRoundTripEquals(body);
        assertThat(TipTapMarkdown.toMarkdown(body)).contains(":::socle-json");
    }

    @Test
    void roundTrip_inlineMarks_boldItalicCodeStrikeAndCombined() {
        assertRoundTripEquals(doc(paraWithMarks(
                text("plain "),
                marked("gras", "bold"),
                text(" "),
                marked("italique", "italic"),
                text(" "),
                marked("code", "code"),
                text(" "),
                marked("barre", "strike"),
                text(" "),
                marked("lesdeux", "bold", "italic")
        )));
    }

    @Test
    void codeBlock_literalDirectives_neverInterpreted() {
        String literal = "Ligne ::transclusion{documentId=\"" + TARGET + "\"}\n:::socle-json\n{\"x\":1}\n:::";
        Map<String, Object> body = doc(codeBlock(literal, null));
        assertRoundTripEquals(body);
    }

    @Test
    void paragraph_startingWithDoubleColon_staysParagraph() {
        Map<String, Object> body = doc(para("::pas-une-directive réelle"));
        assertRoundTripEquals(body);
        Map<String, Object> back = TipTapMarkdown.fromMarkdown(TipTapMarkdown.toMarkdown(body));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) back.get("content");
        assertThat(content.getFirst().get("type")).isEqualTo("paragraph");
    }

    @Test
    void invalidTransclusionUuid_serializedAsSocleJson_notDropped() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("type", TransclusionResolver.NODE_TYPE);
        bad.put("attrs", Map.of(TransclusionResolver.ATTR_DOCUMENT_ID, "not-a-uuid"));
        assertRoundTripEquals(doc(bad));
        assertThat(TipTapMarkdown.toMarkdown(doc(bad))).contains(":::socle-json");
    }

    @ParameterizedTest(name = "lossLess_case{index}")
    @MethodSource("lossCases")
    void roundTrip_lossCases(Map<String, Object> body) {
        assertRoundTripEquals(body);
    }

    static Stream<Map<String, Object>> lossCases() {
        UUID t = TARGET;
        // 1 — liste imbriquée / multi-para / tx dans liste
        Map<String, Object> nestedList = bulletList(
                listItem(para("A"), bulletList(listItem(para("nested")))),
                listItem(para("B1"), para("B2")),
                listItem(transclusion(t))
        );
        // 1b — blockquote multi-para + tx
        Map<String, Object> quote = blockquote(para("q1"), para("q2"), transclusion(t));
        // 2 — transclusion avec attr extra
        Map<String, Object> txCollapsed = new LinkedHashMap<>();
        txCollapsed.put("type", "transclusion");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("documentId", t.toString());
        attrs.put("collapsed", true);
        txCollapsed.put("attrs", attrs);
        // 3 — codeBlock language
        Map<String, Object> codeLang = codeBlock("System.out.println(1);", "java");
        // 3b — codeBlock with ``` inside
        Map<String, Object> codeFence = codeBlock("avant\n```\napres", "md");
        // 4 — paragraphes qui ressemblent à MD structure
        Map<String, Object> lookalikes = doc(
                para("- pas une liste"),
                para("1. pas ordonné"),
                para("# pas un titre"),
                para("> pas une citation")
        );
        // 5 — littéraux * ` ~
        Map<String, Object> literals = doc(para("a * b ` c ~ d"));
        // 6 — hardBreak
        Map<String, Object> withBreak = paraWithMarks(text("ligne1"), hardBreak(), text("ligne2"));
        // 7 — strike déjà couvert ; mark inconnu
        Map<String, Object> unknownMark = paraWithMarks(marked("x", "underline"));
        // 8 — paragraphes vides
        Map<String, Object> emptyParas = doc(para("a"), emptyPara(), para("b"));
        // 9 — inline inconnu
        Map<String, Object> unknownInline = new LinkedHashMap<>();
        unknownInline.put("type", "paragraph");
        Map<String, Object> mention = new LinkedHashMap<>();
        mention.put("type", "mention");
        mention.put("attrs", Map.of("id", "u1"));
        unknownInline.put("content", List.of(mention, text(" hi")));

        return Stream.of(
                doc(nestedList),
                doc(quote),
                doc(txCollapsed),
                doc(codeLang),
                doc(codeFence),
                lookalikes,
                literals,
                doc(withBreak),
                doc(unknownMark),
                emptyParas,
                doc(unknownInline)
        );
    }

    @Test
    void property_randomStarterKitDocs_500_fixedSeed() {
        Random rng = new Random(42L);
        for (int i = 0; i < 500; i++) {
            Map<String, Object> body = randomDoc(rng, 0);
            assertRoundTripEquals(body);
        }
    }

    private static Map<String, Object> randomDoc(Random rng, int depth) {
        int n = 1 + rng.nextInt(depth > 2 ? 2 : 4);
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            blocks.add(randomBlock(rng, depth));
        }
        return doc(blocks.toArray(Map[]::new));
    }

    private static Map<String, Object> randomBlock(Random rng, int depth) {
        return switch (rng.nextInt(depth > 3 ? 6 : 10)) {
            case 0 -> para(randomText(rng));
            case 1 -> heading(1 + rng.nextInt(3), randomText(rng));
            case 2 -> codeBlock(randomText(rng) + (rng.nextBoolean() ? "\n```\nx" : ""),
                    rng.nextBoolean() ? "js" : null);
            case 3 -> transclusion(UUID.randomUUID());
            case 4 -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("type", "callout");
                c.put("attrs", Map.of("tone", rng.nextBoolean() ? "info" : "warning"));
                c.put("content", List.of(para(randomText(rng))));
                yield c;
            }
            case 5 -> emptyPara();
            case 6 -> bulletList(listItem(para(randomText(rng))), listItem(transclusion(UUID.randomUUID())));
            case 7 -> blockquote(para(randomText(rng)), transclusion(UUID.randomUUID()));
            case 8 -> paraWithMarks(
                    text(randomText(rng)),
                    marked(randomText(rng), "bold"),
                    marked(randomText(rng), "strike"),
                    hardBreak(),
                    marked(randomText(rng), "italic")
            );
            default -> paraWithMarks(marked(randomText(rng), "unknownMark" + rng.nextInt(3)));
        };
    }

    private static String randomText(Random rng) {
        String[] samples = {
                "alpha", "beta * star", "code ` x", "dash - item", "hash # t",
                "quote > q", "tilde ~ t", "num 1. x", "::almost", "ok"
        };
        return samples[rng.nextInt(samples.length)];
    }

    private static void assertRoundTripEquals(Map<String, Object> body) {
        Map<String, Object> back = TipTapMarkdown.fromMarkdown(TipTapMarkdown.toMarkdown(body));
        assertThat(TipTapMarkdown.normalize(back))
                .as("round-trip failed for %s\nMD:\n%s", body, TipTapMarkdown.toMarkdown(body))
                .isEqualTo(TipTapMarkdown.normalize(body));
    }

    @SafeVarargs
    private static Map<String, Object> doc(Map<String, Object>... blocks) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "doc");
        d.put("content", List.of(blocks));
        return d;
    }

    private static Map<String, Object> para(String text) {
        return paraWithMarks(textNode(text));
    }

    private static Map<String, Object> emptyPara() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "paragraph");
        return p;
    }

    @SafeVarargs
    private static Map<String, Object> paraWithMarks(Map<String, Object>... parts) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "paragraph");
        p.put("content", List.of(parts));
        return p;
    }

    private static Map<String, Object> heading(int level, String text) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("type", "heading");
        h.put("attrs", Map.of("level", level));
        h.put("content", List.of(textNode(text)));
        return h;
    }

    private static Map<String, Object> textNode(String text) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "text");
        t.put("text", text);
        return t;
    }

    private static Map<String, Object> text(String s) {
        return textNode(s);
    }

    private static Map<String, Object> hardBreak() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("type", "hardBreak");
        return h;
    }

    private static Map<String, Object> marked(String text, String... markTypes) {
        Map<String, Object> t = textNode(text);
        List<Map<String, Object>> marks = new ArrayList<>();
        for (String m : markTypes) {
            marks.add(Map.of("type", m));
        }
        t.put("marks", marks);
        return t;
    }

    private static Map<String, Object> transclusion(UUID id) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", TransclusionResolver.NODE_TYPE);
        n.put("attrs", Map.of(TransclusionResolver.ATTR_DOCUMENT_ID, id.toString()));
        return n;
    }

    private static Map<String, Object> codeBlock(String text, String language) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "codeBlock");
        if (language != null) {
            n.put("attrs", Map.of("language", language));
        }
        n.put("content", List.of(textNode(text)));
        return n;
    }

    @SafeVarargs
    private static Map<String, Object> bulletList(Map<String, Object>... items) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("type", "bulletList");
        l.put("content", List.of(items));
        return l;
    }

    @SafeVarargs
    private static Map<String, Object> listItem(Map<String, Object>... children) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "listItem");
        item.put("content", List.of(children));
        return item;
    }

    @SafeVarargs
    private static Map<String, Object> blockquote(Map<String, Object>... children) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "blockquote");
        b.put("content", List.of(children));
        return b;
    }
}
