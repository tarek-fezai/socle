// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import eu.socle.web.ApiErrors;
import eu.socle.web.CodedStatusException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TipTapContentValidatorTest {

    private final TipTapContentValidator validator = new TipTapContentValidator();

    @Test
    void validExistingDocument_unchanged() {
        Map<String, Object> body = doc(
                paragraph("Objectif"),
                Map.of(
                        "type", "heading",
                        "attrs", Map.of("level", 2),
                        "content", List.of(text("Suite"))
                ),
                Map.of(
                        "type", "image",
                        "attrs", Map.of(
                                "id", "11111111-1111-1111-1111-111111111111",
                                "alt", "schéma",
                                "filename", "a.png",
                                "mediaType", "image/png",
                                "sizeBytes", 12)
                ),
                Map.of(
                        "type", "attachment",
                        "attrs", Map.of(
                                "id", "22222222-2222-2222-2222-222222222222",
                                "filename", "a.pdf",
                                "mediaType", "application/pdf",
                                "sizeBytes", 45)
                )
        );
        validator.validate(body);
    }

    @Test
    void unknownNodeType_is400ContentInvalid() {
        Map<String, Object> body = doc(Map.of("type", "drawio", "attrs", Map.of()));
        assertInvalid(body, "$", "drawio");
    }

    @Test
    void unknownAttribute_is400ContentInvalid() {
        Map<String, Object> body = doc(Map.of(
                "type", "paragraph",
                "attrs", Map.of("evil", true),
                "content", List.of(text("x"))
        ));
        assertInvalid(body, ".attrs.evil", "evil");
    }

    @Test
    void javascriptHref_is400ContentInvalid() {
        Map<String, Object> body = doc(Map.of(
                "type", "paragraph",
                "content", List.of(Map.of(
                        "type", "text",
                        "text", "clic",
                        "marks", List.of(Map.of(
                                "type", "link",
                                "attrs", Map.of("href", "javascript:alert(1)")
                        ))
                ))
        ));
        assertInvalid(body, "href", "schéma");
    }

    @Test
    void buttonWithHttps_ok() {
        validator.validate(doc(Map.of(
                "type", "button",
                "attrs", Map.of("label", "Ouvrir", "href", "https://example.com/x")
        )));
    }

    @Test
    void orderedListTypeAttrFromTipTap_ok() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("start", 1);
        attrs.put("type", null);
        validator.validate(doc(Map.of(
                "type", "orderedList",
                "attrs", attrs,
                "content", List.of(Map.of(
                        "type", "listItem",
                        "content", List.of(paragraph("Un"))
                ))
        )));
    }

    @Test
    void dateInvalid_is400() {
        assertInvalid(doc(Map.of("type", "date", "attrs", Map.of("value", "15/10/2026"))),
                "value", "ISO");
    }

    @Test
    void tableCellMerge_is400() {
        assertInvalid(doc(Map.of(
                "type", "table",
                "content", List.of(Map.of(
                        "type", "tableRow",
                        "content", List.of(Map.of(
                                "type", "tableCell",
                                "attrs", Map.of("colspan", 2),
                                "content", List.of(paragraph("a"))
                        ))
                ))
        )), "colspan", "fusion");
    }

    private static void assertInvalid(Map<String, Object> body, String pathPart, String reasonPart) {
        assertThatThrownBy(() -> new TipTapContentValidator().validate(body))
                .isInstanceOf(CodedStatusException.class)
                .satisfies(ex -> {
                    CodedStatusException c = (CodedStatusException) ex;
                    assertThat(c.getStatusCode().value()).isEqualTo(400);
                    assertThat(c.getCode()).isEqualTo(ApiErrors.CONTENT_INVALID);
                    assertThat(c.getReason()).contains(pathPart).containsIgnoringCase(reasonPart.substring(0, 1));
                    // message contains path + reason
                    assertThat(c.getReason()).containsIgnoringCase(
                            reasonPart.length() > 3 ? reasonPart.substring(0, 3) : reasonPart);
                });
    }

    private static Map<String, Object> doc(Map<String, Object>... blocks) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "doc");
        d.put("content", List.of(blocks));
        return d;
    }

    private static Map<String, Object> paragraph(String t) {
        return Map.of("type", "paragraph", "content", List.of(text(t)));
    }

    private static Map<String, Object> text(String t) {
        return Map.of("type", "text", "text", t);
    }
}
