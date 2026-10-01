// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.template;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateBodySupportTest {

    @Test
    void substituteVariables_replacesKnownInTextNodesOnly() {
        Map<String, Object> body = doc(
                para("Bonjour {{auteur}} — {{date}}"),
                Map.of("type", "placeholder", "attrs", Map.of("hint", "{{titre}} reste"))
        );
        Map<String, Object> out = TemplateBodySupport.substituteVariables(body, Map.of(
                "date", "2026-10-01",
                "auteur", "Alice",
                "espace", "Eng",
                "titre", "Doc"));
        assertThat(out.toString()).contains("Bonjour Alice — 2026-10-01");
        assertThat(out.toString()).contains("{{titre}} reste"); // hint attrs not substituted
    }

    @Test
    void substituteVariables_unknownLeftAsIs() {
        Map<String, Object> body = doc(para("{{inconnu}} et {{titre}}"));
        Map<String, Object> out = TemplateBodySupport.substituteVariables(body, Map.of("titre", "X"));
        assertThat(out.toString()).contains("{{inconnu}}").contains("X");
    }

    @Test
    void listPlaceholderHints_collectsHints() {
        Map<String, Object> body = doc(
                Map.of("type", "placeholder", "attrs", Map.of("hint", "Objectif")),
                Map.of("type", "placeholder", "attrs", Map.of("hint", "Périmètre"))
        );
        assertThat(TemplateBodySupport.listPlaceholderHints(body))
                .containsExactly("Objectif", "Périmètre");
        assertThat(TemplateBodySupport.placeholderConflictMessage(
                TemplateBodySupport.listPlaceholderHints(body)))
                .contains("Objectif").contains("Périmètre");
    }

    @Test
    void deepCopy_independent() {
        Map<String, Object> body = doc(para("a"));
        Map<String, Object> copy = TemplateBodySupport.deepCopy(body);
        @SuppressWarnings("unchecked")
        List<Object> content = (List<Object>) copy.get("content");
        content.clear();
        assertThat(body.get("content")).asList().isNotEmpty();
    }

    private static Map<String, Object> doc(Object... blocks) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "doc");
        d.put("content", List.of(blocks));
        return d;
    }

    private static Map<String, Object> para(String text) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "text");
        t.put("text", text);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "paragraph");
        p.put("content", List.of(t));
        return p;
    }
}
