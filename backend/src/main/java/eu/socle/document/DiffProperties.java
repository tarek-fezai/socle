// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.document;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Comparaison de versions (écran Historique) — plafond de taille d'un côté du diff.
 * Au-delà : 413 sur {@code GET /documents/{id}/versions/{a}/compare/{b}}.
 */
@Component
@ConfigurationProperties(prefix = "socle.diff")
public class DiffProperties {

    public static final int DEFAULT_MAX_LINES = 20000;

    /** Nombre maximal de lignes Markdown par version comparée. Défaut : 20000. */
    private int maxLines = DEFAULT_MAX_LINES;

    @PostConstruct
    void validate() {
        if (maxLines <= 0) {
            throw new IllegalStateException(
                    "socle.diff.max-lines doit être > 0 (reçu: " + maxLines + ")");
        }
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }
}
