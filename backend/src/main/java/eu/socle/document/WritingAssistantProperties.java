// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.document;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Assistant d'écriture (écran d'édition) — seuils configurables à l'échelle de l'instance.
 */
@Component
@ConfigurationProperties(prefix = "socle.writing-assistant")
public class WritingAssistantProperties {

    public static final int DEFAULT_LONG_PARAGRAPH_WORDS = 120;

    /** Nombre de mots au-delà duquel un paragraphe est signalé. Défaut : 120. */
    private int longParagraphWords = DEFAULT_LONG_PARAGRAPH_WORDS;

    @PostConstruct
    void validate() {
        if (longParagraphWords <= 0) {
            throw new IllegalStateException(
                    "socle.writing-assistant.long-paragraph-words doit être > 0 (reçu: "
                            + longParagraphWords + ")");
        }
    }

    public int getLongParagraphWords() {
        return longParagraphWords;
    }

    public void setLongParagraphWords(int longParagraphWords) {
        this.longParagraphWords = longParagraphWords;
    }
}
