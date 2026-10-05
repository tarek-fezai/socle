// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * La fixture {@code tiptap/editor-contract.json} est produite par le vitest frontend
 * ({@code tiptapEditorContract.fixture.test.ts}) via {@code editor.getJSON()}.
 */
class TipTapEditorContractFixtureTest {

    private final TipTapContentValidator validator = new TipTapContentValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void editorContractFixture_isAccepted() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("tiptap/editor-contract.json")) {
            assert in != null : "fixture tiptap/editor-contract.json manquante";
            Map<String, Object> body = mapper.readValue(in, new TypeReference<>() {});
            assertThatCode(() -> validator.validate(body)).doesNotThrowAnyException();
        }
    }
}
