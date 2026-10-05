// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.comment;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentBodySanitizerTest {

    @Test
    void stripsHtmlAndScript() {
        String out = CommentBodySanitizer.sanitize("Hello <script>alert(1)</script> **world**");
        assertThat(out).isEqualTo("Hello alert(1) **world**");
        assertThat(out).doesNotContain("<script>");
    }

    @Test
    void neutralizesJavascriptUrls() {
        String out = CommentBodySanitizer.sanitize("voir [x](javascript:alert(1)) ok");
        assertThat(out).contains("[x](#)");
        assertThat(out).doesNotContain("javascript:");
    }

    @Test
    void keepsHttpsLinks() {
        String out = CommentBodySanitizer.sanitize("[doc](https://example.com/a)");
        assertThat(out).isEqualTo("[doc](https://example.com/a)");
    }

    @Test
    void rejectsEmptyAndTooLong() {
        assertThatThrownBy(() -> CommentBodySanitizer.sanitize("   "))
                .isInstanceOf(IllegalArgumentException.class);
        String big = "a".repeat(CommentBodySanitizer.MAX_LENGTH + 1);
        assertThatThrownBy(() -> CommentBodySanitizer.sanitize(big))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

class CommentAnchorResolverTest {

    @Test
    void plainTextFromTipTap() {
        Map<String, Object> body = Map.of(
                "type", "doc",
                "content", java.util.List.of(
                        Map.of("type", "paragraph", "content", java.util.List.of(
                                Map.of("type", "text", "text", "Alpha"))),
                        Map.of("type", "paragraph", "content", java.util.List.of(
                                Map.of("type", "text", "text", "Beta passage cible")))
                ));
        String plain = CommentAnchorResolver.plainText(body);
        assertThat(plain).contains("Alpha").contains("Beta passage cible");
    }

    @Test
    void resolve_withPrefixSuffix_holdsWhenOtherParagraphChanges() {
        String v3 = "Intro\nPassage ancré ici\nSuite\n";
        TextQuoteAnchor a = CommentAnchorResolver.buildFromOffset(
                v3, v3.indexOf("Passage ancré ici"), "Passage ancré ici", null, 3);
        assertThat(a).isNotNull();

        String v4 = "Intro modifiée ailleurs\nPassage ancré ici\nSuite\n";
        var r = CommentAnchorResolver.resolve(v4, a);
        assertThat(r.attached()).isTrue();
        assertThat(v4.substring(r.startOffset(), r.endOffset())).isEqualTo("Passage ancré ici");
    }

    @Test
    void resolve_detachedWhenExactRewritten() {
        String v3 = "Avant Passage ancré ici Après";
        TextQuoteAnchor a = CommentAnchorResolver.buildFromOffset(
                v3, v3.indexOf("Passage ancré ici"), "Passage ancré ici", "b1", 3);
        String v5 = "Avant Passage complètement réécrit Après";
        var r = CommentAnchorResolver.resolve(v5, a);
        assertThat(r.attached()).isFalse();
    }

    @Test
    void resolve_fallsBackToExactAlone() {
        TextQuoteAnchor a = TextQuoteAnchor.of("cible", "prefix-gone-", "-suffix-gone", null, 1);
        var r = CommentAnchorResolver.resolve("xxx cible yyy", a);
        assertThat(r.attached()).isTrue();
    }
}
