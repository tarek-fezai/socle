// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import eu.socle.document.DocumentDtos.CompareHunk;
import eu.socle.document.DocumentDtos.CompareLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownLineDiffTest {

    private static List<String> numbered(int n) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            lines.add("ligne " + i);
        }
        return lines;
    }

    @Test
    void identicalContent_hasNoChangeAndOneCollapsedHunk() {
        List<String> lines = numbered(10);
        var r = MarkdownLineDiff.compare(lines, lines);
        assertThat(r.added()).isZero();
        assertThat(r.removed()).isZero();
        assertThat(r.hunks()).hasSize(1);
        assertThat(r.hunks().getFirst().lines()).isEmpty();
        assertThat(r.hunks().getFirst().collapsedUnchanged()).isEqualTo(10);
    }

    @Test
    void middleChange_keepsThreeContextLines_andCollapsesTheRest() {
        List<String> before = numbered(30);
        List<String> after = new ArrayList<>(before);
        after.set(14, "ligne 99"); // ligne 15 → modifiée

        var r = MarkdownLineDiff.compare(before, after);

        assertThat(r.added()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(1);
        assertThat(r.hunks()).hasSize(3);
        CompareHunk head = r.hunks().get(0);
        CompareHunk body = r.hunks().get(1);
        CompareHunk tail = r.hunks().get(2);
        assertThat(head.lines()).isEmpty();
        assertThat(head.collapsedUnchanged()).isEqualTo(11); // lignes 1..11
        assertThat(body.collapsedUnchanged()).isZero();
        assertThat(body.lines()).extracting(CompareLine::kind)
                .containsExactly("context", "context", "context", "del", "add",
                        "context", "context", "context");
        assertThat(tail.lines()).isEmpty();
        assertThat(tail.collapsedUnchanged()).isEqualTo(12); // lignes 19..30

        CompareLine del = body.lines().get(3);
        CompareLine add = body.lines().get(4);
        assertThat(del.oldNo()).isEqualTo(15);
        assertThat(del.newNo()).isNull();
        assertThat(add.oldNo()).isNull();
        assertThat(add.newNo()).isEqualTo(15);
        assertThat(body.lines().get(0).oldNo()).isEqualTo(12);
        assertThat(body.lines().get(0).newNo()).isEqualTo(12);
    }

    @Test
    void closeChanges_areMergedIntoSingleHunk() {
        List<String> before = numbered(20);
        List<String> after = new ArrayList<>(before);
        after.set(4, "autre 5");
        after.set(9, "autre 10"); // écart de 4 lignes entre les deux modifications (≤ 2×3)

        var r = MarkdownLineDiff.compare(before, after);

        long expanded = r.hunks().stream().filter(h -> h.collapsedUnchanged() == 0).count();
        assertThat(expanded).isEqualTo(1);
        assertThat(r.added()).isEqualTo(2);
        assertThat(r.removed()).isEqualTo(2);
    }

    @Test
    void modifiedLine_getsWordLevelSpans() {
        List<String> before = List.of("Le chat dort sur le canapé");
        List<String> after = List.of("Le chien dort sur le canapé");

        var r = MarkdownLineDiff.compare(before, after);
        List<CompareLine> lines = r.hunks().getFirst().lines();
        CompareLine del = lines.stream().filter(l -> "del".equals(l.kind())).findFirst().orElseThrow();
        CompareLine add = lines.stream().filter(l -> "add".equals(l.kind())).findFirst().orElseThrow();

        assertThat(del.spans()).isNotNull();
        assertThat(del.spans()).anyMatch(s -> "del".equals(s.kind()) && s.text().equals("chat"));
        assertThat(add.spans()).anyMatch(s -> "add".equals(s.kind()) && s.text().equals("chien"));
        // Les spans reconstruisent la ligne.
        assertThat(String.join("", del.spans().stream().map(s -> s.text()).toList()))
                .isEqualTo(before.getFirst());
        assertThat(String.join("", add.spans().stream().map(s -> s.text()).toList()))
                .isEqualTo(after.getFirst());
    }

    @Test
    void completelyDifferentLines_haveNoSpans() {
        var r = MarkdownLineDiff.compare(List.of("aaaa bbbb"), List.of("xxxx yyyy zzzz"));
        for (CompareLine l : r.hunks().getFirst().lines()) {
            assertThat(l.spans()).isNull();
        }
    }

    @Test
    void headerIsNearestHeadingAboveTheHunk_ignoringCodeFences() {
        List<String> before = new ArrayList<>(List.of(
                "# Titre", "", "intro", "", "## Section B", "", "```", "# pas un titre", "```", ""));
        for (int i = 0; i < 10; i++) {
            before.add("para " + i);
            before.add("");
        }
        List<String> after = new ArrayList<>(before);
        after.set(after.size() - 2, "para modifiée");

        var r = MarkdownLineDiff.compare(before, after);

        CompareHunk changed = r.hunks().stream().filter(h -> h.collapsedUnchanged() == 0).findFirst().orElseThrow();
        assertThat(changed.header()).isEqualTo("Section B");
        // Le hunk replié initial est avant la première modification : titre au-dessus de son début.
        assertThat(r.hunks().getFirst().header()).isEqualTo("");
    }

    @Test
    void headingOfTipTapNodeInSocleJson_isUsedAsHeader() {
        assertThat(MarkdownLineDiff.headingText(
                "{\"type\":\"heading\",\"attrs\":{\"level\":2,\"id\":\"x\"},"
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Titre riche\"}]}"))
                .isEqualTo("Titre riche");
        assertThat(MarkdownLineDiff.headingText("{\"type\":\"paragraph\"}")).isNull();
        assertThat(MarkdownLineDiff.headingText("## Un titre ##")).isEqualTo("Un titre");
        assertThat(MarkdownLineDiff.headingText("texte # pas titre")).isNull();
    }

    @Test
    void blankLinesAreNotCounted_butStayInHunks() {
        var r = MarkdownLineDiff.compare(List.of(), List.of("a", "", "b"));
        assertThat(r.added()).isEqualTo(2);
        assertThat(r.removed()).isZero();
        assertThat(r.hunks().getFirst().lines()).hasSize(3);
        assertThat(MarkdownLineDiff.nonBlankCount(List.of("a", "", "b"))).isEqualTo(2);
    }

    @Test
    void transclusionDirectiveIsDiffedAsPlainText() {
        String directive = "::transclusion{documentId=\"11111111-1111-1111-1111-111111111111\"}";
        var r = MarkdownLineDiff.compare(
                List.of("a", directive), List.of("a", "::transclusion{documentId=\"22222222-2222-2222-2222-222222222222\"}"));
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(1);
        assertThat(r.hunks().getFirst().lines()).anyMatch(l -> "del".equals(l.kind()) && l.text().equals(directive));
    }

    @Test
    void splitLines_dropsTrailingNewline() {
        assertThat(MarkdownLineDiff.splitLines("a\n\nb\n")).containsExactly("a", "", "b");
        assertThat(MarkdownLineDiff.splitLines("")).isEmpty();
        assertThat(MarkdownLineDiff.splitLines(null)).isEmpty();
    }
}
